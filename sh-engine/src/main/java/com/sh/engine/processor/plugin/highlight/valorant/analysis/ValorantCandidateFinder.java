package com.sh.engine.processor.plugin.highlight.valorant.analysis;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.processor.plugin.highlight.analysis.SeiMediaClock;
import com.sh.engine.model.highlight.core.HighlightEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/** 无畏契约专属：弹幕词表、击杀/死亡分组与候选预算；不作为其他游戏的默认规则。 */
@Component
public class ValorantCandidateFinder {
    private static final int CHAT_WINDOW_SECONDS = 20;
    private static final int CHAT_STEP_SECONDS = 10;

    @Getter
    @AllArgsConstructor
    public static class Chat {
        private final File video;
        private final double second;
        private final String text;
        private final double weight;
    }

    @Getter
    @AllArgsConstructor
    public static class Candidate {
        private final File video;
        private double start;
        private double end;
        private double rank;
        private final Set<String> triggers;
    }

    public List<Chat> align(File chatFile, List<SeiMediaClock.Source> sources, JSONObject coverage) throws IOException {
        List<Chat> result = new ArrayList<>();
        if (!chatFile.isFile()) {
            coverage.put("chat", "missing; OCR recall remains available");
            return result;
        }
        int rows = 0, invalid = 0, unaligned = 0;
        for (String line : Files.readAllLines(chatFile.toPath(), StandardCharsets.UTF_8)) {
            String[] fields = line.split("__SEP__", 4);
            if (fields.length < 3) { invalid++; continue; }
            rows++;
            long wall;
            try { wall = Long.parseLong(fields[1]) * 1000L; }
            catch (NumberFormatException error) { invalid++; continue; }
            boolean found = false;
            for (SeiMediaClock.Source source : sources) {
                Double second = source.locate(wall);
                if (second == null) { continue; }
                String text = fields[2].trim();
                result.add(new Chat(source.getVideo(), second, text, activityWeight(text)));
                found = true;
                break;
            }
            if (!found) { unaligned++; }
        }
        coverage.put("chatRows", rows);
        coverage.put("aligned", result.size());
        coverage.put("unaligned", unaligned);
        coverage.put("invalid", invalid);
        coverage.put("clock", "SEI type 100 ts milliseconds paired with packet PTS; edge extrapolation <=3s; no audience-latency correction");
        return result;
    }

    /** 召回以发言量为主；疑似推广软降权，反应词最多增加20%，不因长度直接删消息。 */
    static double activityWeight(String text) {
        if (text.isEmpty()) { return 0; }
        if (isPromotion(text)) { return 0.25; }
        return text.matches(".*(哈哈|笑死|牛|帅|漂亮|666|六六|卧槽|我靠|逆天|离谱|五杀|四杀|三杀|残局|王牌|可惜|好枪|好拆|爆了).*") ? 1.2 : 1;
    }

    private static boolean isPromotion(String text) {
        return text.matches(".*(福袋|点点关注|粉丝团|抽奖|礼物任务).*");
    }

    /** 只限制本窗口的重复长模板/推广贡献；没有用户ID，不能把短促群体反应当作单人刷屏。 */
    private double chatActivity(List<Chat> chats, double start, double end) {
        Map<String, Integer> repeats = new HashMap<>();
        double score = 0;
        for (Chat chat : chats) {
            if (chat.second < start || chat.second >= end || chat.weight <= 0) { continue; }
            int count = repeats.merge(chat.text, 1, Integer::sum);
            if (count > 3 && (chat.text.length() > 12 || isPromotion(chat.text))) { continue; }
            score += chat.weight;
        }
        return score;
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) { return 0; }
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }

    public List<Candidate> find(List<SeiMediaClock.Source> sources, List<Chat> chats, List<HighlightEvent> events) {
        List<Candidate> proposals = new ArrayList<>();
        for (SeiMediaClock.Source source : sources) {
            File video = source.getVideo();
            List<Chat> localChats = chats.stream().filter(chat -> video.equals(chat.video)).collect(Collectors.toList());
            int windows = source.getDuration() < CHAT_WINDOW_SECONDS ? 0
                    : (int) Math.ceil((source.getDuration() - CHAT_WINDOW_SECONDS) / CHAT_STEP_SECONDS) + 1;
            double[] activity = new double[windows];
            for (int i = 0; i < activity.length; i++) {
                double start = Math.min(i * CHAT_STEP_SECONDS, source.getDuration() - CHAT_WINDOW_SECONDS);
                activity[i] = chatActivity(localChats, start, start + CHAT_WINDOW_SECONDS);
            }
            double sourceBaseline = median(Arrays.stream(activity).boxed().collect(Collectors.toList()));
            for (int i = 0; i < activity.length; i++) {
                List<Double> background = new ArrayList<>();
                // 前后40～120秒取不重叠窗口；源边界之外不补零。
                for (int offset = -12; offset <= 12; offset += 2) {
                    int neighbor = i + offset;
                    if (Math.abs(offset) >= 4 && neighbor >= 0 && neighbor < activity.length) { background.add(activity[neighbor]); }
                }
                double baseline = background.isEmpty() ? sourceBaseline : median(background);
                if (activity[i] >= 6 && activity[i] >= baseline * 1.5) {
                    double start = Math.min(i * CHAT_STEP_SECONDS, source.getDuration() - CHAT_WINDOW_SECONDS);
                    double peak = start + CHAT_WINDOW_SECONDS / 2.0;
                    // 压缩弹幕量的长尾，避免换权重后线性挤掉同一预算内的OCR候选。
                    double excess = (activity[i] - baseline) / Math.sqrt(Math.max(1, baseline));
                    proposals.add(candidate(video, peak - 50, peak + 20, source.getDuration(),
                            3 * Math.log1p(excess), "chat-burst"));
                }
            }
            List<HighlightEvent> local = events.stream().filter(e -> video.equals(e.getSourceVideo()))
                    .sorted().collect(Collectors.toList());
            List<HighlightEvent> life = new ArrayList<>();
            for (HighlightEvent event : local) {
                if (!life.isEmpty() && event.getSecondFromVideoStart() - life.get(0).getSecondFromVideoStart() > 70) {
                    addCombat(proposals, source, life);
                    life.clear();
                }
                if ("SELF_KILL".equals(event.getEventType())) { life.add(event); }
                if ("SELF_DEATH".equals(event.getEventType())) {
                    addCombat(proposals, source, life);
                    life.clear();
                }
            }
            addCombat(proposals, source, life);
        }
        proposals.sort(Comparator.comparing((Candidate c) -> c.video.getName()).thenComparingDouble(c -> c.start));
        List<Candidate> merged = new ArrayList<>();
        for (Candidate candidate : proposals) {
            Candidate last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.video.equals(candidate.video) && candidate.start <= last.end
                    && Math.max(last.end, candidate.end) - last.start <= 110) {
                last.end = Math.max(last.end, candidate.end);
                last.rank = Math.max(last.rank, candidate.rank);
                last.triggers.addAll(candidate.triggers);
            } else { merged.add(candidate); }
        }
        // 每个源分段保留一定覆盖，再统一限额，避免单个热闹片段垄断所有审片预算。
        List<Candidate> selected = new ArrayList<>();
        for (SeiMediaClock.Source source : sources) {
            merged.stream().filter(c -> c.video.equals(source.getVideo()))
                    .sorted(Comparator.comparingDouble(Candidate::getRank).reversed()).limit(8).forEach(selected::add);
        }
        selected.sort(Comparator.comparingDouble(Candidate::getRank).reversed());
        return selected.stream().limit(32).collect(Collectors.toList());
    }

    private void addCombat(List<Candidate> proposals, SeiMediaClock.Source source, List<HighlightEvent> life) {
        if (life.size() < 2) { return; }
        proposals.add(candidate(source.getVideo(), life.get(0).getSecondFromVideoStart() - 15,
                life.get(life.size() - 1).getSecondFromVideoStart() + 18,
                source.getDuration(), life.size() * 3, "ocr-multi-kill-candidate"));
    }

    private Candidate candidate(File video, double start, double end, double duration, double rank, String trigger) {
        return new Candidate(video, Math.max(0, start), Math.min(duration - 0.2, end), rank,
                new LinkedHashSet<>(Collections.singletonList(trigger)));
    }
}
