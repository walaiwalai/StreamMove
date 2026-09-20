package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.model.danmaku.OcrFrameEvidence;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DanmakuHighlightEvidenceCollectorTest {
    @Test
    public void shouldSampleRangeAndKeepReliableUniqueOcrText() throws Exception {
        File sourceVideo = Files.createTempFile("danmaku-evidence-", ".mp4").toFile();
        try {
            DanmakuHighlightEvidenceCollector collector = new DanmakuHighlightEvidenceCollector();
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenAnswer(invocation -> new InMemoryVideoFrame(
                            invocation.getArgument(1), new byte[]{1, 2, 3}));
            HighlightOcrClient ocrClient = mock(HighlightOcrClient.class);
            when(ocrClient.recognize(any(byte[].class), anyString())).thenReturn(Arrays.asList(
                    new OcrTextDetection("状态已更新", 0.95f, Collections.emptyList()),
                    new OcrTextDetection("状态已更新", 0.90f, Collections.emptyList()),
                    new OcrTextDetection("低置信文字", 0.20f, Collections.emptyList())));
            inject(collector, "frameExtractor", frameExtractor);
            inject(collector, "ocrClient", ocrClient);

            List<OcrFrameEvidence> evidence =
                    collector.collect(sourceVideo, 2, 14);

            Assert.assertEquals(4, evidence.size());
            Assert.assertEquals(2, evidence.get(0).getTimestampSeconds());
            Assert.assertEquals(13, evidence.get(3).getTimestampSeconds());
            for (OcrFrameEvidence frame : evidence) {
                Assert.assertEquals(1, frame.getTexts().size());
                Assert.assertEquals("状态已更新", frame.getTexts().get(0));
            }
        } finally {
            sourceVideo.delete();
        }
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuHighlightEvidenceCollector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

}
