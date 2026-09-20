package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 将长复核窗拆为重叠的局部事实窗，避免模型在多事件中任选一个。 */
@Component
public class HighlightFocusedWindowPlanner {
    static final int MAXIMUM_FOCUSED_SECONDS = 45;
    private static final int MAXIMUM_UNSPLIT_SECONDS = 55;
    private static final int OVERLAP_SECONDS = 15;

    public List<HighlightClipRange> plan(HighlightClipRange reviewWindow) {
        if (reviewWindow == null || reviewWindow.durationSeconds() <= 0) {
            throw new IllegalArgumentException("invalid dense review window");
        }
        if (reviewWindow.durationSeconds() <= MAXIMUM_UNSPLIT_SECONDS) {
            return Collections.singletonList(reviewWindow);
        }
        List<HighlightClipRange> windows = new ArrayList<>();
        int step = MAXIMUM_FOCUSED_SECONDS - OVERLAP_SECONDS;
        int start = reviewWindow.getStartSecond();
        while (start + MAXIMUM_FOCUSED_SECONDS < reviewWindow.getEndSecond()) {
            windows.add(new HighlightClipRange(
                    start, start + MAXIMUM_FOCUSED_SECONDS));
            start += step;
        }
        int finalStart = reviewWindow.getEndSecond() - MAXIMUM_FOCUSED_SECONDS;
        HighlightClipRange finalWindow = new HighlightClipRange(
                finalStart, reviewWindow.getEndSecond());
        HighlightClipRange last = windows.isEmpty()
                ? null : windows.get(windows.size() - 1);
        if (last == null || last.getStartSecond() != finalWindow.getStartSecond()) {
            windows.add(finalWindow);
        }
        return Collections.unmodifiableList(windows);
    }
}
