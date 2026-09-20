package com.sh.engine.service.impl;

import com.sh.engine.model.danmaku.DanmakuRecallWindow;
import com.sh.engine.model.danmaku.SessionVideoRange;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按整场直播的弹幕分布对滑动窗口做相对排名。
 * 这里只识别互动强度和讨论变化，不推断具体内容或是否最终成片。
 */
@Component
@Slf4j
public class DanmakuCandidateRanker {
    private static final Pattern EMOTE_PATTERN = Pattern.compile("\\[[^\\]]{1,12}]");
    private static final Pattern REPEATED_CHARACTER_PATTERN = Pattern.compile("(.)\\1{3,}");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern REACTION_PATTERN = Pattern.compile(
            "哈哈|笑|绷|乐|没想到|意外|离谱|精彩|高能|厉害|漂亮|可惜|[?!？！]");
    private static final Pattern CHANGE_PATTERN = Pattern.compile(
            "刚.{0,12}就|结果|果然|突然|居然|竟然|原来|最后|下一秒|本来|"
                    + "反而|还以为|差一点|终于|怎么|为什么");
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "上票|人气票|点赞|点关注|刷点|嘉年华|礼物|互关|粉丝团|退会员|"
                    + "主播.*支持|家人们|兄弟们.*票|关注主播|点点关注");

    private static final int STORY_WINDOW_SECONDS = 80;
    private static final int STORY_WINDOW_STEP_SECONDS = 5;
    private static final int CONTEXT_BEFORE_SIGNAL_WINDOW_SECONDS = 60;
    private static final int MAXIMUM_CANDIDATE_COUNT = 15;
    private static final int MINIMUM_UNIQUE_TEXT_COUNT = 4;
    private static final int LOCAL_BASELINE_RADIUS_SECONDS = 300;
    private static final double MINIMUM_RELATIVE_SCORE = 60D;
    private static final double MINIMUM_LOCAL_BURST_RATIO = 1.2D;

    public List<DanmakuRecallWindow> rank(
            List<SimpleDanmaku> orderedDanmakus,
            int timelineEnd,
            List<SessionVideoRange> videoRanges) {
        List<WindowMetrics> metrics = collectMetrics(
                orderedDanmakus, timelineEnd, videoRanges);
        List<DanmakuRecallWindow> eligible = scoreRelativeToSession(metrics);
        log.info("danmaku session ranking, windows: {}, eligible: {}",
                metrics.size(), eligible.size());
        eligible.sort(Comparator.comparingDouble(
                DanmakuRecallWindow::getScore).reversed());
        return selectNonOverlapping(eligible);
    }

    private List<WindowMetrics> collectMetrics(
            List<SimpleDanmaku> danmakus,
            int timelineEnd,
            List<SessionVideoRange> videoRanges) {
        List<WindowMetrics> metrics = new ArrayList<>();
        int left = 0;
        int right = 0;
        for (int start = 0; start < timelineEnd; start += STORY_WINDOW_STEP_SECONDS) {
            int end = Math.min(timelineEnd, start + STORY_WINDOW_SECONDS);
            SessionVideoRange videoRange = findVideoRange(start, videoRanges);
            if (videoRange == null || !videoRange.containsRange(start, end)) {
                continue;
            }
            while (left < danmakus.size() && danmakus.get(left).getTime() < start) {
                left++;
            }
            right = Math.max(right, left);
            while (right < danmakus.size() && danmakus.get(right).getTime() < end) {
                right++;
            }
            WindowMetrics windowMetrics = calculateMetrics(
                    danmakus.subList(left, right), start, end);
            if (windowMetrics.uniqueTextCount >= MINIMUM_UNIQUE_TEXT_COUNT) {
                metrics.add(windowMetrics);
            }
        }
        return metrics;
    }

    private WindowMetrics calculateMetrics(
            List<SimpleDanmaku> danmakus, int windowStart, int windowEnd) {
        Map<String, Integer> textCounts = new HashMap<>();
        Set<String> earlyTexts = new HashSet<>();
        Set<String> lateTexts = new HashSet<>();
        Set<Integer> activeSlices = new HashSet<>();
        int midpoint = windowStart + (windowEnd - windowStart) / 2;
        for (SimpleDanmaku danmaku : danmakus) {
            String text = normalizeText(danmaku.getText());
            if (text.length() < 2 || NOISE_PATTERN.matcher(text).find()) {
                continue;
            }
            textCounts.put(text, textCounts.getOrDefault(text, 0) + 1);
            if (danmaku.getTime() < midpoint) {
                earlyTexts.add(text);
            } else {
                lateTexts.add(text);
            }
            activeSlices.add(Math.max(0,
                    ((int) danmaku.getTime() - windowStart) / STORY_WINDOW_STEP_SECONDS));
        }

        int reactionCount = countMatches(textCounts.keySet(), REACTION_PATTERN);
        int changeCount = countMatches(textCounts.keySet(), CHANGE_PATTERN);
        int cappedOccurrenceCount = textCounts.values().stream()
                .mapToInt(count -> Math.min(count, 3))
                .sum();
        double uniqueCount = textCounts.size();
        double intensity = Math.log1p(uniqueCount)
                + Math.log1p(cappedOccurrenceCount) * 0.5D;
        double reactionRate = uniqueCount == 0 ? 0D : reactionCount / uniqueCount;
        double changeRate = uniqueCount == 0 ? 0D : changeCount / uniqueCount;
        double repeatStrength = uniqueCount == 0
                ? 0D : (cappedOccurrenceCount - uniqueCount) / uniqueCount;
        double coverage = activeSlices.size()
                / (double) (STORY_WINDOW_SECONDS / STORY_WINDOW_STEP_SECONDS);
        return new WindowMetrics(
                windowStart, windowEnd, textCounts.size(), intensity,
                reactionRate, changeRate, repeatStrength, coverage,
                phaseChangeScore(earlyTexts, lateTexts));
    }

    private double phaseChangeScore(Set<String> earlyTexts, Set<String> lateTexts) {
        if (earlyTexts.size() < 2 || lateTexts.size() < 2) {
            return 0D;
        }
        double earlyReactionRate = countMatches(earlyTexts, REACTION_PATTERN)
                / (double) earlyTexts.size();
        double lateReactionRate = countMatches(lateTexts, REACTION_PATTERN)
                / (double) lateTexts.size();
        double earlyChangeRate = countMatches(earlyTexts, CHANGE_PATTERN)
                / (double) earlyTexts.size();
        double lateChangeRate = countMatches(lateTexts, CHANGE_PATTERN)
                / (double) lateTexts.size();
        return (Math.abs(earlyReactionRate - lateReactionRate)
                + Math.abs(earlyChangeRate - lateChangeRate)) / 2D;
    }

    private List<DanmakuRecallWindow> scoreRelativeToSession(
            List<WindowMetrics> metrics) {
        if (metrics.isEmpty()) {
            return new ArrayList<>();
        }
        List<Double> intensities = metricValues(metrics, MetricType.INTENSITY);
        List<Double> reactionRates = metricValues(metrics, MetricType.REACTION_RATE);
        List<Double> changeRates = metricValues(metrics, MetricType.CHANGE_RATE);
        List<Double> repeatStrengths = metricValues(metrics, MetricType.REPEAT_STRENGTH);
        List<Double> coverages = metricValues(metrics, MetricType.COVERAGE);
        List<Double> phaseChanges = metricValues(metrics, MetricType.PHASE_CHANGE);
        List<Double> bursts = new ArrayList<>();
        for (WindowMetrics metric : metrics) {
            bursts.add(localBurstRatio(metric, metrics));
        }

        List<DanmakuRecallWindow> result = new ArrayList<>();
        for (int index = 0; index < metrics.size(); index++) {
            WindowMetrics metric = metrics.get(index);
            double intensityPercentile = percentile(metric.intensity, intensities);
            double reactionPercentile = percentile(metric.reactionRate, reactionRates);
            double changePercentile = percentile(metric.changeRate, changeRates);
            double burstPercentile = percentile(bursts.get(index), bursts);
            double phasePercentile = percentile(metric.phaseChange, phaseChanges);
            double score = intensityPercentile * 25D
                    + reactionPercentile * 20D
                    + changePercentile * 20D
                    + burstPercentile * 15D
                    + phasePercentile * 10D
                    + percentile(metric.coverage, coverages) * 5D
                    + percentile(metric.repeatStrength, repeatStrengths) * 5D;
            if (isEligible(metric, bursts.get(index), score,
                    intensityPercentile, reactionPercentile,
                    changePercentile, phasePercentile)) {
                result.add(new DanmakuRecallWindow(
                        metric.startSecond, metric.endSecond, score));
            }
        }
        return result;
    }

    private boolean isEligible(
            WindowMetrics metric,
            double burstRatio,
            double score,
            double intensityPercentile,
            double reactionPercentile,
            double changePercentile,
            double phasePercentile) {
        if (score < MINIMUM_RELATIVE_SCORE) {
            return false;
        }
        boolean hasBurst = burstRatio >= MINIMUM_LOCAL_BURST_RATIO
                && intensityPercentile >= 0.75D;
        boolean hasReaction = metric.reactionRate > 0D
                && reactionPercentile >= 0.75D;
        boolean hasChange = metric.changeRate > 0D
                && changePercentile >= 0.75D;
        boolean hasPhaseChange = metric.phaseChange > 0D
                && phasePercentile >= 0.75D;
        return hasBurst || hasReaction || hasChange || hasPhaseChange;
    }

    private List<Double> metricValues(
            List<WindowMetrics> metrics, MetricType metricType) {
        List<Double> values = new ArrayList<>();
        for (WindowMetrics metric : metrics) {
            values.add(metricType.value(metric));
        }
        return values;
    }

    private double localBurstRatio(
            WindowMetrics target, List<WindowMetrics> metrics) {
        List<Double> baseline = new ArrayList<>();
        for (WindowMetrics metric : metrics) {
            int distance = Math.abs(metric.startSecond - target.startSecond);
            if (distance >= STORY_WINDOW_SECONDS
                    && distance <= LOCAL_BASELINE_RADIUS_SECONDS) {
                baseline.add(metric.intensity);
            }
        }
        if (baseline.isEmpty()) {
            baseline = metricValues(metrics, MetricType.INTENSITY);
        }
        double median = median(baseline);
        return median <= 0D ? 1D : target.intensity / median;
    }

    private double median(List<Double> source) {
        List<Double> values = new ArrayList<>(source);
        values.sort(Double::compareTo);
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2D
                : values.get(middle);
    }

    /** 使用同值中位排名，避免大量相同特征同时得到最高分。 */
    private double percentile(double value, List<Double> values) {
        if (values.size() <= 1) {
            return 0.5D;
        }
        int less = 0;
        int equal = 0;
        for (Double item : values) {
            int comparison = Double.compare(item, value);
            if (comparison < 0) {
                less++;
            } else if (comparison == 0) {
                equal++;
            }
        }
        double middleRank = less + (equal - 1) / 2D;
        return middleRank / (values.size() - 1D);
    }

    private String normalizeText(String text) {
        String normalized = StringUtils.trimToEmpty(text).toLowerCase(Locale.ROOT);
        normalized = EMOTE_PATTERN.matcher(normalized).replaceAll("");
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll("");
        normalized = REPEATED_CHARACTER_PATTERN.matcher(normalized).replaceAll("$1$1$1");
        return SYMBOL_PATTERN.matcher(normalized).replaceAll("");
    }

    private int countMatches(Set<String> texts, Pattern pattern) {
        int count = 0;
        for (String text : texts) {
            if (pattern.matcher(text).find()) {
                count++;
            }
        }
        return count;
    }

    private List<DanmakuRecallWindow> selectNonOverlapping(
            List<DanmakuRecallWindow> windows) {
        List<DanmakuRecallWindow> selected = new ArrayList<>();
        for (DanmakuRecallWindow window : windows) {
            boolean overlaps = selected.stream().anyMatch(existing ->
                    window.overlapsWithContext(
                            existing, CONTEXT_BEFORE_SIGNAL_WINDOW_SECONDS));
            if (overlaps) {
                continue;
            }
            selected.add(window);
            if (selected.size() >= MAXIMUM_CANDIDATE_COUNT) {
                break;
            }
        }
        return selected;
    }

    private SessionVideoRange findVideoRange(
            int second, List<SessionVideoRange> ranges) {
        for (SessionVideoRange range : ranges) {
            if (range.contains(second)) {
                return range;
            }
        }
        return null;
    }

    private static final class WindowMetrics {
        private final int startSecond;
        private final int endSecond;
        private final int uniqueTextCount;
        private final double intensity;
        private final double reactionRate;
        private final double changeRate;
        private final double repeatStrength;
        private final double coverage;
        private final double phaseChange;

        private WindowMetrics(
                int startSecond,
                int endSecond,
                int uniqueTextCount,
                double intensity,
                double reactionRate,
                double changeRate,
                double repeatStrength,
                double coverage,
                double phaseChange) {
            this.startSecond = startSecond;
            this.endSecond = endSecond;
            this.uniqueTextCount = uniqueTextCount;
            this.intensity = intensity;
            this.reactionRate = reactionRate;
            this.changeRate = changeRate;
            this.repeatStrength = repeatStrength;
            this.coverage = coverage;
            this.phaseChange = phaseChange;
        }
    }

    private enum MetricType {
        INTENSITY {
            @Override
            double value(WindowMetrics metric) {
                return metric.intensity;
            }
        },
        REACTION_RATE {
            @Override
            double value(WindowMetrics metric) {
                return metric.reactionRate;
            }
        },
        CHANGE_RATE {
            @Override
            double value(WindowMetrics metric) {
                return metric.changeRate;
            }
        },
        REPEAT_STRENGTH {
            @Override
            double value(WindowMetrics metric) {
                return metric.repeatStrength;
            }
        },
        COVERAGE {
            @Override
            double value(WindowMetrics metric) {
                return metric.coverage;
            }
        },
        PHASE_CHANGE {
            @Override
            double value(WindowMetrics metric) {
                return metric.phaseChange;
            }
        };

        abstract double value(WindowMetrics metric);
    }
}
