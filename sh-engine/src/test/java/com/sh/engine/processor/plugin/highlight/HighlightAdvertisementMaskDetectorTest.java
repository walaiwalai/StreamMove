package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.model.highlight.core.ScoredVideoInterval;
import com.sh.engine.processor.plugin.highlight.HighlightAdvertisementMaskDetector.AdvertisementClassificationResult;
import com.sh.engine.processor.plugin.highlight.HighlightAdvertisementMaskDetector.AdvertisementDecision;
import com.sh.engine.service.LlmService;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HighlightAdvertisementMaskDetectorTest {

    @Test
    public void shouldBuildMaskFromTrustedOcrBoxesAndLlmCandidateIds() throws Exception {
        File snapshot = Files.createTempFile("highlight-ad-mask-", ".png").toFile();
        try {
            ImageIO.write(createFrame(), "png", snapshot);
            HighlightAdvertisementMaskDetector detector = new HighlightAdvertisementMaskDetector();
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenReturn(new InMemoryVideoFrame(
                            10, Files.readAllBytes(snapshot.toPath())));
            HighlightOcrClient ocrClient = mock(HighlightOcrClient.class);
            when(ocrClient.recognize(any(byte[].class), anyString())).thenReturn(Arrays.asList(
                    new OcrTextDetection("ROG 键盘", 0.98f,
                            Arrays.asList(28, 155, 100, 155, 100, 170, 28, 170)),
                    new OcrTextDetection("EV63", 0.96f,
                            Arrays.asList(30, 183, 95, 183, 95, 198, 30, 198))));
            LlmService llmService = mock(LlmService.class);
            when(llmService.chat(anyString(), any())).thenAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                assertTrue(prompt.contains("ROG 键盘"));
                AdvertisementClassificationResult result =
                        new AdvertisementClassificationResult();
                AdvertisementDecision first = new AdvertisementDecision();
                first.setCandidateId("F01-C001");
                first.setConfidence(0.98);
                AdvertisementDecision second = new AdvertisementDecision();
                second.setCandidateId("F01-C002");
                second.setConfidence(0.97);
                result.setAdvertisements(Arrays.asList(first, second));
                return result;
            });
            inject(detector, "frameExtractor", frameExtractor);
            inject(detector, "ocrClient", ocrClient);
            inject(detector, "llmService", llmService);
            inject(detector, "regionResolver", new AdvertisementRegionResolver());

            ScoredVideoInterval interval = new ScoredVideoInterval(
                    new File("P01.mp4"), 10, 40, 5, 1, 0, Collections.emptyList());
            HighlightMaskPlan plan = detector.detect(
                    Collections.singletonList(interval), snapshot.getParentFile());

            assertEquals(1, plan.getMasks().size());
            assertTrue(plan.getMasks().get(0).getWidth() > 0.2);
            assertTrue(plan.getMasks().get(0).getHeight() > 0.1);
        } finally {
            Files.deleteIfExists(snapshot.toPath());
        }
    }

    private BufferedImage createFrame() {
        BufferedImage image = new BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.WHITE);
        graphics.drawRect(15, 135, 125, 75);
        graphics.dispose();
        return image;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

}
