package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.VisualFrameEvidence;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.VisualChangeTimestampSelector;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DanmakuVisualEvidenceCollectorTest {

    @Test
    public void shouldCollectFortyEightFramesAcrossDenseReviewWindow() throws Exception {
        File sourceVideo = Files.createTempFile("dense-visual-", ".mp4").toFile();
        File evidenceDirectory = Files.createTempDirectory("dense-visual-frames-").toFile();
        try {
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenAnswer(invocation -> new InMemoryVideoFrame(
                            invocation.getArgument(1), new byte[]{1, 2, 3}));
            DanmakuVisualEvidenceCollector collector = new DanmakuVisualEvidenceCollector();
            inject(collector, "frameExtractor", frameExtractor);

            List<VisualFrameEvidence> frames = collector.collectDense(
                    sourceVideo, 100, 175, evidenceDirectory);

            Assert.assertEquals(48, frames.size());
            Assert.assertEquals(100, frames.get(0).getTimestampSeconds());
            Assert.assertEquals(174, frames.get(47).getTimestampSeconds());
            Assert.assertTrue(new File(frames.get(0).getEvidencePath()).isFile());
        } finally {
            File[] generated = evidenceDirectory.listFiles();
            if (generated != null) {
                for (File file : generated) {
                    file.delete();
                }
            }
            evidenceDirectory.delete();
            sourceVideo.delete();
        }
    }

    @Test
    public void shouldUseAdaptiveTimestampsForPublicationFrames() throws Exception {
        File sourceVideo = Files.createTempFile("publication-visual-", ".mp4").toFile();
        File evidenceDirectory = Files.createTempDirectory(
                "publication-visual-frames-").toFile();
        try {
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenAnswer(invocation -> new InMemoryVideoFrame(
                            invocation.getArgument(1), new byte[]{1, 2, 3}));
            VisualChangeTimestampSelector selector = mock(
                    VisualChangeTimestampSelector.class);
            Set<Integer> selected = new TreeSet<>();
            for (int second = 100; second < 148; second++) {
                selected.add(second);
            }
            when(selector.select(sourceVideo, 100, 160, 48)).thenReturn(selected);
            DanmakuVisualEvidenceCollector collector = new DanmakuVisualEvidenceCollector();
            inject(collector, "frameExtractor", frameExtractor);
            inject(collector, "changeTimestampSelector", selector);

            List<VisualFrameEvidence> frames = collector.collectPublication(
                    sourceVideo, 100, 160, evidenceDirectory);

            Assert.assertEquals(48, frames.size());
            Assert.assertEquals(147, frames.get(47).getTimestampSeconds());
        } finally {
            File[] generated = evidenceDirectory.listFiles();
            if (generated != null) {
                for (File file : generated) {
                    file.delete();
                }
            }
            evidenceDirectory.delete();
            sourceVideo.delete();
        }
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuVisualEvidenceCollector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
