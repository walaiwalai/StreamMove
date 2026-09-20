package com.sh.engine.model.danmaku;

import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 密集复核窗口所需的本地时间上下文。负责把直播累计时间映射为文件内时间，
 * 并从召回信号窗内定位去重后的短时弹幕反应中心。
 */
public final class HighlightReviewContext {
    private static final int BURST_WINDOW_SECONDS = 10;
    private static final int BURST_WINDOW_STEP_SECONDS = 2;
    private static final int MAXIMUM_SAME_TEXT_CONTRIBUTION = 3;

    private final int candidateStartSecond;
    private final int candidateEndSecond;
    private final int signalStartSecond;
    private final int signalEndSecond;
    private final int sessionToFileOffset;
    private final List<SimpleDanmaku> danmakus;

    public HighlightReviewContext(
            int candidateStartSecond,
            int candidateEndSecond,
            int signalStartSecond,
            int signalEndSecond,
            int sessionToFileOffset,
            List<SimpleDanmaku> danmakus) {
        if (candidateStartSecond < 0 || candidateEndSecond <= candidateStartSecond) {
            throw new IllegalArgumentException("invalid highlight review candidate range");
        }
        this.candidateStartSecond = candidateStartSecond;
        this.candidateEndSecond = candidateEndSecond;
        this.signalStartSecond = Math.max(candidateStartSecond, signalStartSecond);
        this.signalEndSecond = Math.min(candidateEndSecond, signalEndSecond);
        this.sessionToFileOffset = sessionToFileOffset;
        this.danmakus = danmakus == null ? Collections.emptyList() : danmakus;
    }

    /**
     * 返回召回信号窗内最密集的去重弹幕短窗中心；没有有效弹幕时退回信号窗中心。
     */
    public int findDanmakuBurstCenterSecond() {
        if (signalEndSecond <= signalStartSecond) {
            return candidateStartSecond
                    + (candidateEndSecond - candidateStartSecond) / 2;
        }
        int bestStart = signalStartSecond;
        int bestScore = -1;
        for (int start = signalStartSecond;
             start < signalEndSecond;
             start += BURST_WINDOW_STEP_SECONDS) {
            int score = scoreBurst(start, Math.min(
                    signalEndSecond, start + BURST_WINDOW_SECONDS));
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        if (bestScore <= 0) {
            return signalStartSecond + (signalEndSecond - signalStartSecond) / 2;
        }
        int burstEnd = Math.min(signalEndSecond, bestStart + BURST_WINDOW_SECONDS);
        return findMessageCenter(bestStart, burstEnd);
    }

    private int scoreBurst(int startSecond, int endSecond) {
        Map<String, Integer> counts = new HashMap<>();
        for (SimpleDanmaku danmaku : danmakus) {
            if (danmaku == null) {
                continue;
            }
            int fileSecond = (int) danmaku.getTime() - sessionToFileOffset;
            String text = normalize(danmaku.getText());
            if (fileSecond >= startSecond && fileSecond < endSecond
                    && StringUtils.isNotBlank(text)) {
                counts.put(text, counts.getOrDefault(text, 0) + 1);
            }
        }
        int cappedOccurrences = counts.values().stream()
                .mapToInt(count -> Math.min(count, MAXIMUM_SAME_TEXT_CONTRIBUTION))
                .sum();
        return counts.size() * 2 + cappedOccurrences;
    }

    private int findMessageCenter(int startSecond, int endSecond) {
        List<Integer> timestamps = new ArrayList<>();
        for (SimpleDanmaku danmaku : danmakus) {
            if (danmaku == null || StringUtils.isBlank(normalize(danmaku.getText()))) {
                continue;
            }
            int fileSecond = (int) danmaku.getTime() - sessionToFileOffset;
            if (fileSecond >= startSecond && fileSecond < endSecond) {
                timestamps.add(fileSecond);
            }
        }
        Collections.sort(timestamps);
        int middle = timestamps.size() / 2;
        return timestamps.size() % 2 == 0
                ? (timestamps.get(middle - 1) + timestamps.get(middle)) / 2
                : timestamps.get(middle);
    }

    private String normalize(String text) {
        return StringUtils.deleteWhitespace(
                StringUtils.trimToEmpty(text).toLowerCase(Locale.ROOT));
    }

    public int getCandidateStartSecond() {
        return candidateStartSecond;
    }

    public int getCandidateEndSecond() {
        return candidateEndSecond;
    }

    /** 返回信号窗起点；信号窗无效时退回候选中心。 */
    public int findSignalStartOrCandidateCenter() {
        return signalEndSecond > signalStartSecond
                ? signalStartSecond
                : candidateStartSecond
                + (candidateEndSecond - candidateStartSecond) / 2;
    }
}
