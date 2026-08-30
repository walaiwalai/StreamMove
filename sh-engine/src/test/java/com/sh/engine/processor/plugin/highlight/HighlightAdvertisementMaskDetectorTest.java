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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HighlightAdvertisementMaskDetectorTest {

    @Test
    public void shouldBuildMaskFromTrustedOcrBoxesAndLlmCandidateIds() throws Exception {
        File snapshot = Files.createTempFile("highlight-ad-mask-", ".png").toFile();
        try {
            ImageIO.write(createFrame(), "png", snapshot);
            HighlightAdvertisementMaskDetector detector = new HighlightAdvertisementMaskDetector();
            inject(detector, "frameExtractor", new FixedFrameExtractor(Files.readAllBytes(snapshot.toPath())));
            inject(detector, "ocrClient", new FixedOcrClient());
            inject(detector, "llmService", new AdvertisingLlmService());
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

    private static final class FixedFrameExtractor extends FfmpegFrameExtractor {
        private final byte[] jpegData;

        private FixedFrameExtractor(byte[] jpegData) {
            this.jpegData = jpegData;
        }

        @Override
        public InMemoryVideoFrame extract(File sourceVideo,
                                          int timestampSeconds,
                                          String cropExpression) {
            return new InMemoryVideoFrame(timestampSeconds, jpegData);
        }
    }

    private static final class FixedOcrClient extends HighlightOcrClient {
        @Override
        public List<OcrTextDetection> recognize(byte[] jpegData, String fileName) {
            return Arrays.asList(
                    new OcrTextDetection("ROG 键盘", 0.98f,
                            Arrays.asList(28, 155, 100, 155, 100, 170, 28, 170)),
                    new OcrTextDetection("EV63", 0.96f,
                            Arrays.asList(30, 183, 95, 183, 95, 198, 30, 198)));
        }
    }

    private static final class AdvertisingLlmService implements LlmService {
        @Override
        public <T> T chat(String prompt, Class<T> resultType) {
            assertTrue(prompt.contains("ROG 键盘"));
            AdvertisementClassificationResult result = new AdvertisementClassificationResult();
            AdvertisementDecision first = new AdvertisementDecision();
            first.setCandidateId("F01-C001");
            first.setConfidence(0.98);
            AdvertisementDecision second = new AdvertisementDecision();
            second.setCandidateId("F01-C002");
            second.setConfidence(0.97);
            result.setAdvertisements(Arrays.asList(first, second));
            return resultType.cast(result);
        }

        @Override
        public <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType) {
            return CompletableFuture.completedFuture(chat(prompt, resultType));
        }
    }
}
