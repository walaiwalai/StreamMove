package com.sh.engine.processor.plugin.highlight;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.highlight.FactPreservingCoverStyle;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 仅用生成图的全局统计增强源帧，拒绝采用任何生成图空间像素。 */
@Component
public class FactPreservingCoverStyler {
    static final double MINIMUM_STYLE_GAIN = 0.85;
    static final double MAXIMUM_STYLE_GAIN = 1.15;
    static final double MAXIMUM_MEAN_SHIFT = 18.0;

    public FactPreservingCoverStyle transfer(byte[] sourceJpeg, byte[] qwenJpeg) {
        BufferedImage source = decodeImage(sourceJpeg, "source frame");
        BufferedImage qwen = decodeImage(qwenJpeg, "qwen background");
        BufferedImage normalizedQwen = resize(qwen, source.getWidth(), source.getHeight());
        ChannelStatistics sourceStatistics = ChannelStatistics.from(source);
        ChannelStatistics qwenStatistics = ChannelStatistics.from(normalizedQwen);
        ChannelTransform transform = ChannelTransform.between(
                sourceStatistics, qwenStatistics);
        BufferedImage result = apply(source, transform);
        return new FactPreservingCoverStyle(
                encodeJpeg(result), source.getWidth(), source.getHeight(),
                transform.gains, transform.offsets);
    }

    private BufferedImage apply(BufferedImage source, ChannelTransform transform) {
        BufferedImage result = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int red = transform.apply((rgb >>> 16) & 0xff, 0);
                int green = transform.apply((rgb >>> 8) & 0xff, 1);
                int blue = transform.apply(rgb & 0xff, 2);
                result.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return result;
    }

    private BufferedImage decodeImage(byte[] jpeg, String description) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image != null) {
                return image;
            }
        } catch (IOException e) {
            throw coverError("cannot decode " + description, e);
        }
        throw coverError("unsupported " + description, null);
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private byte[] encodeJpeg(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpg", output)) {
                throw coverError("JPEG writer is unavailable", null);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw coverError("cannot encode fact-preserving cover background", e);
        }
    }

    private StreamerRecordException coverError(String message, Throwable cause) {
        if (cause == null) {
            return new StreamerRecordException(ErrorEnum.COVER_GENERATION_ERROR, message);
        }
        return new StreamerRecordException(ErrorEnum.COVER_GENERATION_ERROR, message, cause);
    }

    private static final class ChannelTransform {
        private final double[] gains;
        private final double[] offsets;

        private ChannelTransform(double[] gains, double[] offsets) {
            this.gains = gains;
            this.offsets = offsets;
        }

        private static ChannelTransform between(
                ChannelStatistics source, ChannelStatistics qwen) {
            double[] gains = new double[3];
            double[] offsets = new double[3];
            for (int channel = 0; channel < 3; channel++) {
                double gain = qwen.standardDeviation[channel]
                        / Math.max(1.0, source.standardDeviation[channel]);
                gains[channel] = Math.max(
                        MINIMUM_STYLE_GAIN, Math.min(MAXIMUM_STYLE_GAIN, gain));
                double meanShift = qwen.mean[channel] - source.mean[channel];
                meanShift = Math.max(
                        -MAXIMUM_MEAN_SHIFT, Math.min(MAXIMUM_MEAN_SHIFT, meanShift));
                offsets[channel] = source.mean[channel] + meanShift
                        - source.mean[channel] * gains[channel];
            }
            return new ChannelTransform(gains, offsets);
        }

        private int apply(int value, int channel) {
            double styled = value * gains[channel] + offsets[channel];
            return (int) Math.round(Math.max(0.0, Math.min(255.0, styled)));
        }
    }

    private static final class ChannelStatistics {
        private final double[] mean;
        private final double[] standardDeviation;

        private ChannelStatistics(double[] mean, double[] standardDeviation) {
            this.mean = mean;
            this.standardDeviation = standardDeviation;
        }

        private static ChannelStatistics from(BufferedImage image) {
            double[] sum = new double[3];
            double[] squaredSum = new double[3];
            long pixelCount = (long) image.getWidth() * image.getHeight();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    accumulate(sum, squaredSum, 0, (rgb >>> 16) & 0xff);
                    accumulate(sum, squaredSum, 1, (rgb >>> 8) & 0xff);
                    accumulate(sum, squaredSum, 2, rgb & 0xff);
                }
            }
            double[] mean = new double[3];
            double[] standardDeviation = new double[3];
            for (int channel = 0; channel < 3; channel++) {
                mean[channel] = sum[channel] / pixelCount;
                double variance = squaredSum[channel] / pixelCount
                        - mean[channel] * mean[channel];
                standardDeviation[channel] = Math.sqrt(Math.max(0.0, variance));
            }
            return new ChannelStatistics(mean, standardDeviation);
        }

        private static void accumulate(
                double[] sum,
                double[] squaredSum,
                int channel,
                int value) {
            sum[channel] += value;
            squaredSum[channel] += (double) value * value;
        }
    }
}
