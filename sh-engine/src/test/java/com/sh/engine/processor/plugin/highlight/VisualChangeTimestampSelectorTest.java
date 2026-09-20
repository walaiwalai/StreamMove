package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VisualChangeTimestampSelectorTest {

    @Test
    public void shouldCombineUniformCoverageWithStrongVisualChange() throws Exception {
        File sourceVideo = Files.createTempFile("visual-change-", ".mp4").toFile();
        try {
            FfmpegFrameExtractor extractor = mock(FfmpegFrameExtractor.class);
            byte[] dark = jpeg(Color.BLACK);
            byte[] bright = jpeg(Color.WHITE);
            when(extractor.streamRange(eq(sourceVideo), eq(100), eq(130),
                    anyString(), any(FfmpegFrameExtractor.FrameConsumer.class)))
                    .thenAnswer(invocation -> {
                        FfmpegFrameExtractor.FrameConsumer consumer = invocation.getArgument(4);
                        for (int second = 100; second < 130; second++) {
                            consumer.accept(new InMemoryVideoFrame(
                                    second, second < 117 ? dark : bright));
                        }
                        return 30;
                    });
            VisualChangeTimestampSelector selector = new VisualChangeTimestampSelector();
            inject(selector, extractor);

            Set<Integer> timestamps = selector.select(sourceVideo, 100, 130, 25);

            Assert.assertEquals(25, timestamps.size());
            Assert.assertTrue(timestamps.contains(100));
            Assert.assertTrue(timestamps.contains(129));
            Assert.assertTrue(timestamps.contains(117));
        } finally {
            sourceVideo.delete();
        }
    }

    private void inject(
            VisualChangeTimestampSelector selector,
            FfmpegFrameExtractor extractor) throws Exception {
        Field field = VisualChangeTimestampSelector.class.getDeclaredField("frameExtractor");
        field.setAccessible(true);
        field.set(selector, extractor);
    }

    private byte[] jpeg(Color color) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Assert.assertTrue(ImageIO.write(image, "jpg", output));
        return output.toByteArray();
    }
}
