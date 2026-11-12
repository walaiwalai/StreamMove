package com.sh.engine.processor.plugin;

import com.sh.engine.model.highlight.core.HighlightProcessContext;
import com.sh.engine.processor.plugin.highlight.AbstractHighlightCutPlugin;
import com.sh.engine.processor.plugin.highlight.HighlightIntervalPlanner;
import com.sh.engine.processor.plugin.highlight.lol.LolHighlightTimelineProvider;
import com.sh.engine.processor.plugin.highlight.valorant.analysis.ValorantHighlightAnalyzer;
import com.sh.engine.processor.plugin.highlight.valorant.ValorantHighlightTimelineProvider;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GameHighlightIsolationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void lolWithEmptyTimelineKeepsTheGenericPlannerPath() throws Exception {
        File recording = temporary.newFolder(); new File(recording, "P01.mp4").createNewFile();
        LoLVodHighLightCutPlugin plugin = new LoLVodHighLightCutPlugin();
        LolHighlightTimelineProvider timeline = mock(LolHighlightTimelineProvider.class);
        when(timeline.buildScoredTimeline(any())).thenReturn(Collections.emptyList());
        HighlightIntervalPlanner planner = mock(HighlightIntervalPlanner.class);
        when(planner.select(anyList(), any())).thenReturn(Collections.emptyList());
        set(LoLVodHighLightCutPlugin.class, plugin, "timelineProvider", timeline);
        set(AbstractHighlightCutPlugin.class, plugin, "intervalPlanner", planner);
        assertTrue(plugin.process(recording.getAbsolutePath()));
        verify(planner).select(eq(Collections.emptyList()), any());
    }

    @Test public void valorantCanRecallFromDanmakuWhenOcrTimelineIsEmpty() throws Exception {
        ValorantVodHighLightCutPlugin plugin = new ValorantVodHighLightCutPlugin();
        ValorantHighlightAnalyzer analyzer = mock(ValorantHighlightAnalyzer.class);
        HighlightProcessContext context = new HighlightProcessContext("/recording", Collections.singletonList(new File("P01.mp4")), new File("/work"));
        when(analyzer.isEnabled()).thenReturn(true);
        when(analyzer.analyze(context, Collections.emptyList())).thenReturn(Collections.emptyList());
        set(ValorantVodHighLightCutPlugin.class, plugin, "analyzer", analyzer);
        assertTrue(plugin.selectIntervals(context, Collections.emptyList()).isEmpty());
        verify(analyzer).analyze(context, Collections.emptyList());
    }

    @Test public void valorantAnalysisExceptionReturnsUnfinishedAndAllowsRetry() throws Exception {
        File recording = temporary.newFolder(); new File(recording, "P01.mp4").createNewFile();
        ValorantVodHighLightCutPlugin plugin = new ValorantVodHighLightCutPlugin();
        ValorantHighlightTimelineProvider timeline = mock(ValorantHighlightTimelineProvider.class);
        when(timeline.buildScoredTimeline(any())).thenReturn(Collections.emptyList());
        ValorantHighlightAnalyzer analyzer = mock(ValorantHighlightAnalyzer.class);
        when(analyzer.isEnabled()).thenReturn(true);
        when(analyzer.analyze(any(), anyList())).thenThrow(new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR));
        set(ValorantVodHighLightCutPlugin.class, plugin, "timelineProvider", timeline);
        set(ValorantVodHighLightCutPlugin.class, plugin, "analyzer", analyzer);
        assertFalse(plugin.process(recording.getAbsolutePath()));
        assertFalse(new File(recording, "highlight.mp4").exists());
        doReturn(Collections.emptyList()).when(analyzer).analyze(any(), anyList());
        assertTrue(plugin.process(recording.getAbsolutePath()));
    }

    @Test public void otherGameTimelineExceptionsUseTheSameFailureContract() throws Exception {
        File recording = temporary.newFolder(); new File(recording, "P01.mp4").createNewFile();
        LoLVodHighLightCutPlugin plugin = new LoLVodHighLightCutPlugin();
        LolHighlightTimelineProvider timeline = mock(LolHighlightTimelineProvider.class);
        when(timeline.buildScoredTimeline(any())).thenThrow(new IllegalStateException("OCR unavailable"));
        set(LoLVodHighLightCutPlugin.class, plugin, "timelineProvider", timeline);
        assertFalse(plugin.process(recording.getAbsolutePath()));
        assertFalse(new File(recording, "highlight.mp4").exists());
    }

    private void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
}
