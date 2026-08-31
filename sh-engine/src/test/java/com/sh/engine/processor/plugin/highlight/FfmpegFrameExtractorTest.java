package com.sh.engine.processor.plugin.highlight;

import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class FfmpegFrameExtractorTest {

    @Test
    public void shouldReadConsecutiveJpegFrames() throws Exception {
        byte[] first = jpeg(Color.RED);
        byte[] second = jpeg(Color.BLUE);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(new byte[]{1, 2, 3});
        stream.write(first);
        stream.write(second);

        FfmpegFrameExtractor extractor = new FfmpegFrameExtractor();
        ByteArrayInputStream input = new ByteArrayInputStream(stream.toByteArray());

        Assert.assertArrayEquals(first, readNextJpeg(extractor, input));
        Assert.assertArrayEquals(second, readNextJpeg(extractor, input));
        Assert.assertNull(readNextJpeg(extractor, input));
    }

    @Test
    public void shouldRetryPreviousSecondWhenRequestedFrameIsUnavailable() throws Exception {
        FfmpegFrameExtractor extractor = new FfmpegFrameExtractor();

        Assert.assertEquals(Arrays.asList(3600, 3599), extractionTimestamps(extractor, 3600));
        Assert.assertEquals(Arrays.asList(0), extractionTimestamps(extractor, 0));
    }

    private byte[] readNextJpeg(FfmpegFrameExtractor extractor, InputStream input) throws Exception {
        Method method = FfmpegFrameExtractor.class.getDeclaredMethod("readNextJpeg", InputStream.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(extractor, input);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> extractionTimestamps(
            FfmpegFrameExtractor extractor,
            int timestampSeconds) throws Exception {
        Method method = FfmpegFrameExtractor.class.getDeclaredMethod(
                "extractionTimestamps", int.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(extractor, timestampSeconds);
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
