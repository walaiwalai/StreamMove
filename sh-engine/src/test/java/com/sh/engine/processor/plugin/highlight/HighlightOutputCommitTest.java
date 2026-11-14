package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.Streamer;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.highlight.core.*;
import com.sh.engine.service.VideoMergeService;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.message.service.MsgSendService;
import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HighlightOutputCommitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void failedExportDoesNotPublishOrPoisonTheNextAttempt() throws Exception {
        File recording = temporary.newFolder("2026-01-01-00-00-00");
        File source = new File(recording, "P01.mp4"); source.createNewFile();
        File work = new File(recording, ".highlight-work/test"); FileUtils.forceMkdir(work);
        // Generic export must not depend on a game-specific analysis report's validity.
        FileUtils.writeStringToFile(new File(work, "analysis.json"), "{broken", StandardCharsets.UTF_8);
        AbstractHighlightCutPlugin plugin = new AbstractHighlightCutPlugin() {
            public String getPluginName() { return "TEST"; }
            protected HighlightTimelineProvider timelineProvider() {
                return context -> Collections.emptyList();
            }
            protected List<ScoredVideoInterval> selectIntervals(HighlightProcessContext context, List<HighlightEvent> events) {
                return Collections.singletonList(new ScoredVideoInterval(source, 1, 20, 8, 1, 0, Collections.emptyList()));
            }
            protected HighlightCutPolicy cutPolicy() { return HighlightCutPolicy.builder().build(); }
            protected HighlightOutputMode outputMode() { return HighlightOutputMode.MERGED_WITH_COVER; }
        };
        HighlightAdvertisementMaskDetector detector = mock(HighlightAdvertisementMaskDetector.class);
        when(detector.detect(anyList(), any(File.class))).thenReturn(HighlightMaskPlan.empty());
        VideoMergeService merge = mock(VideoMergeService.class);
        when(merge.mergeHighlightWithCover(anyList(), any(File.class), anyString(), any(HighlightMaskPlan.class)))
                .thenAnswer(call -> { FileUtils.writeStringToFile(call.getArgument(1), "partial", StandardCharsets.UTF_8); return false; });
        inject(plugin, "advertisementMaskDetector", detector);
        inject(plugin, "videoMergeService", merge);
        inject(plugin, "msgSendService", mock(MsgSendService.class));
        Streamer streamer = new Streamer(); streamer.setName("test"); StreamerInfoHolder.addStreamer(streamer);
        try {
            try {
                plugin.process(recording.getPath());
                fail("Failed export must throw instead of requesting a retry");
            } catch (StreamerRecordException expected) {
                assertEquals(ErrorEnum.PROCESS_PLUGIN_FAILED, expected.getErrorEnum());
            }
            assertFalse(new File(recording, "highlight.mp4").exists());
            when(merge.mergeHighlightWithCover(anyList(), any(File.class), anyString(), any(HighlightMaskPlan.class)))
                    .thenThrow(new IllegalStateException("encoder failed"));
            try {
                plugin.process(recording.getPath());
                fail("Encoder failure must propagate");
            } catch (IllegalStateException expected) {
                assertEquals("encoder failed", expected.getMessage());
            }
            assertTrue(source.isFile());
            assertFalse(new File(recording, "highlight.mp4").exists());
            doAnswer(call -> { FileUtils.writeStringToFile(call.getArgument(1), "complete", StandardCharsets.UTF_8); return true; })
                    .when(merge).mergeHighlightWithCover(anyList(), any(File.class), anyString(), any(HighlightMaskPlan.class));
            assertTrue(plugin.process(recording.getPath()));
            assertEquals("complete", FileUtils.readFileToString(new File(recording, "highlight.mp4"), StandardCharsets.UTF_8));
            assertFalse(new File(work, "highlight.pending.mp4").exists());
        } finally { StreamerInfoHolder.clear(); }
    }

    private void inject(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = AbstractHighlightCutPlugin.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
}
