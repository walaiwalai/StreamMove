package com.sh.engine.processor.plugin.highlight.valorant.analysis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.sh.engine.model.highlight.core.HighlightEvent;
import org.apache.commons.io.FileUtils;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 无畏契约专属：购买阶段、HUD血量、切点和发布标题规则。 */
@Component
@Slf4j
public class ValorantPlanRefiner {
    @Resource private HighlightOcrClient ocrClient;

    static void boundaries(JSONObject decision, ValorantCandidateFinder.Candidate candidate, List<HighlightEvent> events) {
        double start = decision.getDoubleValue("start"), end = decision.getDoubleValue("end");
        JSONArray cited = decision.getJSONArray("evidenceTimes");
        double first = cited.stream().mapToDouble(value -> Double.parseDouble(value.toString())).min().orElse(start);
        double last = cited.stream().mapToDouble(value -> Double.parseDouble(value.toString())).max().orElse(end);
        double preliminaryStart = Math.max(candidate.getStart(), Math.min(start, first - 7));
        double firstKill = events.stream().filter(event -> event.getSourceVideo().equals(candidate.getVideo())
                && "SELF_KILL".equals(event.getEventType()) && event.getSecondFromVideoStart() >= preliminaryStart
                && event.getSecondFromVideoStart() <= end).mapToDouble(HighlightEvent::getSecondFromVideoStart).min().orElse(first);
        first = Math.min(first, firstKill);
        decision.put("modelStart", start); decision.put("modelEnd", end);
        decision.put("start", Math.max(candidate.getStart(), Math.min(start, first - 7)));
        decision.put("end", Math.min(candidate.getEnd(), Math.max(end, last + 3)));
    }

    public void refine(JSONObject decision, File directory) throws IOException {
        List<JSONObject> health = new ArrayList<>();
        int lowHealthFrames = 0;
        List<Boolean> buyPhase = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.matches("frame-\\d+\\.jpg"));
        List<File> frames = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                int second = Integer.parseInt(file.getName().substring(6, 12));
                if (second >= decision.getDoubleValue("start") && second <= decision.getDoubleValue("end")) {
                    frames.add(file);
                }
            }
        }
        frames.sort(Comparator.comparing(File::getName));
        for (File file : frames) {
            BufferedImage frame = ImageIO.read(file);
            List<OcrTextDetection> banner = region(frame, .35, .12, .30, .18, file.getName() + "-round.jpg");
            boolean buying = purchasePhase(banner);
            buyPhase.add(buying);
            JSONObject sample = new JSONObject(true);
            sample.put("frame", file.getName()); sample.put("roundOcr", banner); sample.put("buyPhase", buying);
            try {
                List<OcrTextDetection> detections = region(frame, .30, .91, .06, .08, file.getName() + "-health.jpg");
                sample.put("ocr", detections);
                int hp = -1;
                for (OcrTextDetection text : detections) {
                    if (text.getScore() >= .8 && text.getText().trim().matches("\\d{1,3}")) {
                        int value = Integer.parseInt(text.getText().trim());
                        if (value >= 1 && value <= 100) { hp = value; break; }
                    }
                }
                sample.put("health", hp);
                if (hp > 0 && hp <= 25) { lowHealthFrames++; }
                health.add(sample);
            } catch (IOException | RuntimeException error) {
                log.warn("Optional health OCR unavailable; publish neutral title: {}", file.getName());
                sample.put("error", "health OCR unavailable"); health.add(sample);
            }
        }
        String kind = decision.getString("kind");
        if (("操作".equals(kind) || "残局".equals(kind) || "搞笑".equals(kind)) && crossesRound(buyPhase)) {
            decision.put("valid", false);
            decision.put("validationError", "gameplay story spans an interior purchase phase and subsequent round");
            decision.put("roundEvidence", health);
            return;
        }
        JSONObject evidence = JSON.parseObject(FileUtils.readFileToString(new File(directory, "evidence.json"), StandardCharsets.UTF_8));
        double refinedEnd = decision.getDoubleValue("end");
        if ("操作".equals(kind) || "残局".equals(kind)) {
            double buyStart = tailBuyStart(buyPhase, frames, evidence.getJSONArray("ocr"), refinedEnd);
            if (buyStart >= 0) {
                refinedEnd = Math.min(refinedEnd, buyStart + 3);
                decision.put("tailPolicy", "已进入购买阶段且后面无新击杀证据，保留3秒结果尾部");
            }
        }
        JSONArray speech = evidence.getJSONArray("asr");
        if (decision.containsKey("tailPolicy")) {
            double lastKill = evidence.getJSONArray("ocr").stream().map(value -> (JSONObject) value)
                    .filter(event -> "SELF_KILL".equals(event.getString("eventType"))
                            && event.getDoubleValue("secondFromVideoStart") <= decision.getDoubleValue("end"))
                    .mapToDouble(event -> event.getDoubleValue("secondFromVideoStart")).max().orElse(decision.getDoubleValue("start"));
            refinedEnd = actionSpeechEnd(refinedEnd, speech, Math.max(decision.getDoubleValue("start") + 15, lastKill + 3));
        } else {
            refinedEnd = speechEnd(refinedEnd, speech);
        }
        // 短语音补齐可以向后最多3秒，但不能超过原候选取证范围。
        double candidateEnd = evidence.getDoubleValue("candidateEnd");
        refinedEnd = Math.min(candidateEnd, refinedEnd);
        decision.put("end", refinedEnd);
        JSONArray cited = decision.getJSONArray("evidenceTimes");
        JSONArray retained = new JSONArray();
        for (Object second : cited) {
            if (Double.parseDouble(second.toString()) <= refinedEnd) { retained.add(second); }
        }
        decision.put("modelEvidenceTimes", cited);
        decision.put("evidenceTimes", retained);
        if (retained.size() < 2 || refinedEnd - decision.getDoubleValue("start") < 15) {
            decision.put("valid", false); decision.put("validationError", "insufficient evidence after boundary refinement");
            return;
        }
        String title;
        if ("搞笑".equals(kind)) { title = "对局中的意外一幕"; }
        else if ("互动".equals(kind)) { title = "直播间互动时刻"; }
        else if (lowHealthFrames >= 2) { title = "残血对枪时刻"; }
        else if ("残局".equals(kind)) { title = "残局交锋"; }
        else { title = "对枪交锋"; }
        decision.put("modelTitle", decision.getString("title"));
        decision.put("title", title);
        decision.put("titlePolicy", "仅发布类型与重复OCR可核实的血量状态；角色、击杀数和胜负不从模型单次描述写入标题");
        decision.put("healthEvidence", health);
    }
    static boolean purchasePhase(List<OcrTextDetection> banner) {
        // 大字可能因浅色场景漏识别；同一回合 HUD 内的购买按钮也是购买阶段证据。
        return banner.stream().anyMatch(text -> text.getScore() >= .8
                && text.getText().replaceAll("\\s", "").toUpperCase(Locale.ROOT)
                .matches(".*(购买阶段|購買階段|BUYPHASE).*|[购購][买買]|按[B8][购購][买買]"));
    }

    static double tailBuyStart(List<Boolean> buying, List<File> frames, JSONArray events, double end) {
        for (int i = 1; i < buying.size(); i++) {
            if (buying.get(i) && buying.get(i - 1)) {
                int second = Integer.parseInt(frames.get(i - 1).getName().substring(6, 12));
                if (!hasKillAfter(events, second, end)) { return second; }
            }
        }
        return -1;
    }

    private static boolean hasKillAfter(JSONArray events, double start, double end) {
        if (events == null) { return false; }
        return events.stream().map(value -> (JSONObject) value).anyMatch(event ->
                "SELF_KILL".equals(event.getString("eventType"))
                && event.getDoubleValue("secondFromVideoStart") > start
                && event.getDoubleValue("secondFromVideoStart") <= end);
    }

    static double actionSpeechEnd(double end, JSONArray speech, double earliestEnd) {
        if (speech == null || speech.isEmpty()) { return end; }
        List<JSONObject> segments = new ArrayList<>();
        for (Object value : speech) { segments.add((JSONObject) value); }
        segments.sort(Comparator.comparingDouble(segment -> segment.getDoubleValue("startTime")));
        double from = segments.get(0).getDoubleValue("startTime");
        double to = segments.get(0).getDoubleValue("endTime");
        for (int index = 1; index <= segments.size(); index++) {
            JSONObject next = index < segments.size() ? segments.get(index) : null;
            if (next != null && next.getDoubleValue("startTime") <= to) {
                to = Math.max(to, next.getDoubleValue("endTime"));
                continue;
            }
            if (from < end && to > end) {
                if (to - end <= 3) { return to; }
                return from >= earliestEnd ? from : end;
            }
            if (next != null) {
                from = next.getDoubleValue("startTime"); to = next.getDoubleValue("endTime");
            }
        }
        return end;
    }

    static double speechEnd(double end, JSONArray speech) {
        if (speech == null) { return end; }
        double aligned = end;
        for (Object value : speech) {
            JSONObject segment = (JSONObject) value;
            double to = segment.getDoubleValue("endTime");
            if (segment.getDoubleValue("startTime") < end && to > end && to - end <= 3) {
                aligned = Math.max(aligned, to);
            }
        }
        return aligned;
    }

    static boolean crossesRound(List<Boolean> buyPhase) {
        for (int start = 1; start < buyPhase.size(); start++) {
            if (!buyPhase.get(start) || buyPhase.get(start - 1)) { continue; }
            int end = start;
            while (end < buyPhase.size() && buyPhase.get(end)) { end++; }
            int after = end;
            while (after < buyPhase.size() && !buyPhase.get(after)) { after++; }
            if (end - start >= 2 && after - end >= 2) { return true; }
            start = end;
        }
        return false;
    }

    private List<OcrTextDetection> region(BufferedImage frame, double x, double y, double width,
                                          double height, String name) throws IOException {
        BufferedImage crop = frame.getSubimage((int) (frame.getWidth() * x), (int) (frame.getHeight() * y),
                (int) (frame.getWidth() * width), (int) (frame.getHeight() * height));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(crop, "jpg", bytes);
        return ocrClient.recognize(bytes.toByteArray(), name);
    }

}
