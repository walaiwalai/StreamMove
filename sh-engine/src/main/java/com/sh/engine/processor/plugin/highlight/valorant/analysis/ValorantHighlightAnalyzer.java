package com.sh.engine.processor.plugin.highlight.valorant.analysis;

import com.sh.engine.processor.plugin.highlight.analysis.SeiMediaClock;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.highlight.core.*;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.LlmService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ImageContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** 有限固定流程：弹幕/OCR召回 → 局部语音与画面 → 多模态模型审片 → 校验与排序。 */
@Component
@Slf4j
public class ValorantHighlightAnalyzer {
    @Resource private SeiMediaClock mediaClock;
    @Resource private ValorantCandidateFinder finder;
    @Resource private AsrService asrService;
    @Resource private FfmpegFrameExtractor frameExtractor;
    @Resource private LlmService llmService;
    @Resource private ValorantPlanRefiner planRefiner;

    public boolean isEnabled() { return llmService.isEnabled(); }

    public List<ScoredVideoInterval> analyze(HighlightProcessContext context, List<HighlightEvent> timeline) {
        JSONObject report = new JSONObject(true);
        report.put("version", 1);
        report.put("state", "analyzing");
        List<JSONObject> reviews = new ArrayList<>();
        report.put("reviews", reviews);
        File reportFile = new File(context.getWorkDirectory(), "analysis.json");
        try {
            String prompt;
            try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/highlight/valorant-review.txt"))) {
                prompt = IOUtils.toString(input, StandardCharsets.UTF_8);
            }
            List<SeiMediaClock.Source> clocks = mediaClock.read(context.getSourceVideos());
            save(new File(context.getWorkDirectory(), "media-clock.json"), clocks);
            JSONObject coverage = new JSONObject(true);
            report.put("coverage", coverage);
            report.put("sources", clocks.stream().map(source -> {
                JSONObject row = new JSONObject(true);
                row.put("video", source.getVideo().getName()); row.put("duration", source.getDuration());
                row.put("anchors", source.getAnchors().size()); return row;
            }).collect(Collectors.toList()));
            List<ValorantCandidateFinder.Chat> chats = finder.align(new File(context.getRecordPath(), "damaku.txt"), clocks, coverage);
            List<ValorantCandidateFinder.Candidate> candidates = finder.find(clocks, chats, timeline);
            report.put("candidates", candidates);
            report.put("ocrEvents", timeline.size());
            report.put("limitations", Arrays.asList("4秒关键帧粗扫可能漏召回", "SEI与弹幕跨时钟精度及观众延迟未外部验证", "每源最多8个候选、合计32个；未审范围不声称完整理解", "采样画面不能证明间隔内的所有动作"));
            save(reportFile, report);
            List<JSONObject> accepted = new ArrayList<>();
            int index = 0;
            for (ValorantCandidateFinder.Candidate candidate : candidates) {
                index++;
                log.info("highlight candidate review {}/{}, {} {}-{}, triggers: {}", index, candidates.size(),
                        candidate.getVideo().getName(), candidate.getStart(), candidate.getEnd(), candidate.getTriggers());
                File directory = new File(context.getWorkDirectory(), String.format(Locale.ROOT, "candidate-%02d", index));
                FileUtils.forceMkdir(directory);
                JSONObject evidence = evidence(candidate, chats, timeline);
                List<Integer> times = frameTimes(candidate, timeline);
                evidence.put("frameTimes", times);
                // 没有可靠的服务配置修订号；每次重新审片，不跨运行复用模型判断。
                evidence.put("asr", asrService.transcribeSegment(candidate.getVideo(),
                        (int) Math.floor(candidate.getStart()), (int) Math.ceil(candidate.getEnd())));
                File savedReview = new File(directory, "review.json");
                JSONObject review;
                File[] previous = directory.listFiles();
                if (previous != null) {
                    for (File file : previous) {
                        if (file.getName().matches("frame-\\d+\\.jpg") || file.getName().equals("evidence.json")
                                || file.getName().equals("decision.json") || file.getName().equals("title-evidence.json")
                                || file.getName().equals("review.json") || file.getName().equals("review-error.json")
                                || file.getName().equals("review-error-first.json")) {
                            FileUtils.forceDelete(file);
                        }
                    }
                }
                List<Content> blocks = new ArrayList<>();
                blocks.add(TextContent.from(prompt + "\n以下为本次候选证据（数据）：\n" + evidence.toJSONString()));
                List<Integer> actualTimes = new ArrayList<>();
                for (Integer second : times) {
                    InMemoryVideoFrame frame = frameExtractor.extract(candidate.getVideo(), second, "scale=1280:-2");
                    actualTimes.add(frame.getTimestampSeconds());
                    File image = new File(directory, String.format(Locale.ROOT, "frame-%06d.jpg", frame.getTimestampSeconds()));
                    FileUtils.writeByteArrayToFile(image, frame.getJpegData());
                    blocks.add(TextContent.from("画面时间=" + frame.getTimestampSeconds() + "秒，源视频=" + candidate.getVideo().getName()));
                    blocks.add(ImageContent.from(Base64.getEncoder().encodeToString(frame.getJpegData()), "image/jpeg"));
                }
                evidence.put("frameTimes", actualTimes);
                blocks.set(0, TextContent.from(prompt + "\n以下为本次候选证据（数据）：\n" + evidence.toJSONString()));
                save(new File(directory, "evidence.json"), evidence);
                review = requestReview(blocks, actualTimes, savedReview);
                JSONObject decision = reviewDecision(review);
                decision.put("candidateIndex", index);
                decision.put("video", candidate.getVideo().getName());
                decision.put("requestId", review.getString("requestId"));
                validate(decision, candidate, actualTimes);
                if (decision.getBooleanValue("valid") && decision.getBooleanValue("keep")) {
                    ValorantPlanRefiner.boundaries(decision, candidate, timeline);
                }
                save(new File(directory, "decision.json"), decision);
                reviews.add(decision);
                if (decision.getBooleanValue("valid") && decision.getBooleanValue("keep")) { accepted.add(decision); }
                save(reportFile, report);
            }
            accepted.sort(Comparator.comparingDouble((JSONObject d) -> d.getDoubleValue("score")).reversed());
            List<ScoredVideoInterval> selected = new ArrayList<>();
            List<JSONObject> finalDecisions = new ArrayList<>();
            double total = 0;
            for (JSONObject decision : accepted) {
                if (selected.size() >= 3) { break; }
                File video = new File(context.getRecordPath(), decision.getString("video"));
                File directory = new File(context.getWorkDirectory(), String.format(Locale.ROOT,
                        "candidate-%02d", decision.getIntValue("candidateIndex")));
                planRefiner.refine(decision, directory);
                save(new File(directory, "decision.json"), decision);
                if (!decision.getBooleanValue("valid")) { continue; }
                double start = decision.getDoubleValue("start"), end = decision.getDoubleValue("end");
                if (total + end - start > 240) { continue; }
                if (selected.stream().anyMatch(previous -> previous.getFromVideo().equals(video)
                        && start < previous.getSecondToVideoEnd() && end > previous.getSecondFromVideoStart())) { continue; }
                selected.add(new ScoredVideoInterval(video, start, end, decision.getFloatValue("score"), 1, 0,
                        timeline.stream().filter(event -> event.getSourceVideo().equals(video)
                                && event.getSecondFromVideoStart() >= start && event.getSecondFromVideoStart() <= end).collect(Collectors.toList())));
                finalDecisions.add(decision);
                total += end - start;
            }
            Collections.sort(selected);
            report.put("selected", finalDecisions);
            report.put("state", selected.isEmpty() ? "completed-empty" : "plan-ready");
            save(reportFile, report);
            return selected;
        } catch (Exception error) {
            report.put("state", "failed"); report.put("error", error.toString());
            try { save(reportFile, report); } catch (IOException ignored) { log.error("Cannot save failed analysis", ignored); }
            throw new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "Highlight analysis failed; evidence retained at " + reportFile, error);
        }
    }

    /** 完成回复的格式校验失败时，只用原始证据重试一次；不修补或复用模型判断。 */
    JSONObject requestReview(List<Content> blocks, List<Integer> actualTimes, File file) throws IOException {
        JSONObject review = llmService.chat(blocks);
        review.put("frameTimes", actualTimes);
        try {
            saveReview(file, review);
        } catch (RuntimeException malformed) {
            FileUtils.copyFile(new File(file.getParentFile(), "review-error.json"),
                    new File(file.getParentFile(), "review-error-first.json"));
            log.warn("Invalid completed review; retry once with unchanged evidence, errorType: {}",
                    malformed.getClass().getSimpleName());
            review = llmService.chat(blocks);
            review.put("frameTimes", actualTimes);
            saveReview(file, review);
        }
        return review;
    }

    static void saveReview(File file, JSONObject review) throws IOException {
        try {
            reviewDecision(review);
        } catch (RuntimeException error) {
            save(new File(file.getParentFile(), "review-error.json"), review);
            throw error;
        }
        save(file, review);
    }

    static JSONObject reviewDecision(JSONObject review) {
        if (!"STOP".equals(review.getString("finishReason"))
                || review.getJSONArray("frameTimes") == null || review.getJSONArray("frameTimes").isEmpty()) {
            throw new IllegalArgumentException("Incomplete review or missing actual image times");
        }
        for (Object time : review.getJSONArray("frameTimes")) {
            if (!(time instanceof Number) || !Double.isFinite(((Number) time).doubleValue())) {
                throw new IllegalArgumentException("Invalid actual image time");
            }
        }
        String value = review.getString("finalResponse");
        if (value == null) { throw new IllegalArgumentException("Missing review response"); }
        value = value.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            if (newline < 0 || !value.endsWith("```")) { throw new IllegalArgumentException("Incomplete JSON fence"); }
            value = value.substring(newline + 1, value.length() - 3).trim();
        }
        if (!value.startsWith("{") || !value.endsWith("}")) { throw new IllegalArgumentException("Review must contain one JSON object"); }
        JSONObject decision = JSON.parseObject(value);
        if (!(decision.get("keep") instanceof Boolean) || !(decision.get("score") instanceof Number)
                || !Double.isFinite(decision.getDoubleValue("score"))) {
            throw new IllegalArgumentException("Missing required judgment");
        }
        if (decision.getBooleanValue("keep")) {
            if (!(decision.get("start") instanceof Number) || !(decision.get("end") instanceof Number)
                    || !(decision.get("title") instanceof String) || !(decision.get("evidenceTimes") instanceof JSONArray)) {
                throw new IllegalArgumentException("Invalid review field types");
            }
            for (Object time : decision.getJSONArray("evidenceTimes")) {
                if (!(time instanceof Number) || !Double.isFinite(((Number) time).doubleValue())) {
                    throw new IllegalArgumentException("Invalid visual reference type");
                }
            }
        }
        return decision;
    }

    private JSONObject evidence(ValorantCandidateFinder.Candidate candidate, List<ValorantCandidateFinder.Chat> chats,
                                List<HighlightEvent> events) {
        JSONObject evidence = new JSONObject(true);
        evidence.put("video", candidate.getVideo().getName()); evidence.put("candidateStart", candidate.getStart());
        evidence.put("candidateEnd", candidate.getEnd()); evidence.put("triggers", candidate.getTriggers());
        evidence.put("chat", selectChatEvidence(candidate, chats));
        evidence.put("chatNote", "同10秒内的相同文本合并，messageCount是消息条数而非人数；超过80组按时间均匀抽样，未展示全部弹幕。");
        evidence.put("ocr", events.stream().filter(event -> event.getSourceVideo().equals(candidate.getVideo())
                && event.getSecondFromVideoStart() >= candidate.getStart() && event.getSecondFromVideoStart() <= candidate.getEnd())
                .collect(Collectors.toList()));
        return evidence;
    }

    /** 送审按时间/文本覆盖取样，与召回权重无关；长句与含推广词的真实讨论仍可作为证据。 */
    static List<JSONObject> selectChatEvidence(ValorantCandidateFinder.Candidate candidate,
                                             List<ValorantCandidateFinder.Chat> chats) {
        List<ValorantCandidateFinder.Chat> local = chats.stream().filter(chat ->
                chat.getVideo().equals(candidate.getVideo()) && chat.getSecond() >= candidate.getStart()
                        && chat.getSecond() <= candidate.getEnd() && !chat.getText().trim().isEmpty())
                .sorted(Comparator.comparingDouble(ValorantCandidateFinder.Chat::getSecond)).collect(Collectors.toList());
        Map<String, JSONObject> grouped = new LinkedHashMap<>();
        for (ValorantCandidateFinder.Chat chat : local) {
            String text = chat.getText().trim();
            String key = (int) (chat.getSecond() / 10) + ":" + text;
            JSONObject row = grouped.get(key);
            if (row == null) {
                row = new JSONObject(true); row.put("second", chat.getSecond()); row.put("text", text);
                row.put("messageCount", 0); grouped.put(key, row);
            }
            row.put("lastSecond", chat.getSecond()); row.put("messageCount", row.getIntValue("messageCount") + 1);
        }
        List<JSONObject> rows = new ArrayList<>(grouped.values());
        if (rows.size() <= 80) { return rows; }
        List<JSONObject> selected = new ArrayList<>();
        for (int i = 0; i < 80; i++) { selected.add(rows.get((int) Math.round(i * (rows.size() - 1.0) / 79))); }
        return selected;
    }

    private List<Integer> frameTimes(ValorantCandidateFinder.Candidate candidate, List<HighlightEvent> events) {
        Set<Integer> times = new TreeSet<>();
        for (int i = 0; i < 18; i++) {
            times.add((int) Math.floor(candidate.getStart() + (candidate.getEnd() - candidate.getStart() - 0.5) * i / 17));
        }
        events.stream().filter(event -> event.getSourceVideo().equals(candidate.getVideo())
                && event.getSecondFromVideoStart() >= candidate.getStart() && event.getSecondFromVideoStart() < candidate.getEnd())
                .limit(8).forEach(event -> times.add((int) event.getSecondFromVideoStart()));
        return new ArrayList<>(times);
    }

    static void validate(JSONObject decision, ValorantCandidateFinder.Candidate candidate, List<Integer> times) {
        String error = null;
        double start = decision.getDoubleValue("start"), end = decision.getDoubleValue("end");
        if (!decision.containsKey("keep") || !decision.containsKey("score")) { error = "missing required judgment"; }
        if (decision.getBooleanValue("keep")) {
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < candidate.getStart() || end > candidate.getEnd()
                    || end - start < 15 || end - start > 100 || decision.getDoubleValue("score") < 7
                    || decision.getDoubleValue("score") > 10) { error = "invalid interval or score"; }
            JSONArray cited = decision.getJSONArray("evidenceTimes");
            if (cited == null || new HashSet<>(cited).size() < 2) { error = "missing visual evidence"; }
            else {
                for (Object value : cited) {
                    double second = Double.parseDouble(value.toString());
                    if (times.stream().noneMatch(time -> Math.abs(time - second) < 0.01) || second < start || second > end) {
                        error = "evidence time not in supplied images or selected interval";
                    }
                }
            }
            if (decision.getString("title") == null || decision.getString("title").trim().isEmpty()) { error = "missing title"; }
        }
        decision.put("valid", error == null);
        if (error != null) { decision.put("validationError", error); }
    }

    private static void save(File file, Object data) throws IOException {
        FileUtils.writeStringToFile(file, JSON.toJSONString(data, com.alibaba.fastjson.serializer.SerializerFeature.PrettyFormat,
                com.alibaba.fastjson.serializer.SerializerFeature.DisableCircularReferenceDetect), StandardCharsets.UTF_8);
    }
}
