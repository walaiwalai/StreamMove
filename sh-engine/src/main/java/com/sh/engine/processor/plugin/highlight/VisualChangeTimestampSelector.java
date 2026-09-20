package com.sh.engine.processor.plugin.highlight;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 在固定帧预算内组合均匀覆盖点和局部画面变化点。
 * 低清扫描只决定抽帧时间，最终送审仍重新提取高清真实帧。
 */
@Component
public class VisualChangeTimestampSelector {
    private static final int UNIFORM_FRAME_BUDGET = 24;
    private static final String SCAN_SCALE = "scale=160:90";

    @Resource
    private FfmpegFrameExtractor frameExtractor;

    /**
     * 返回按时间排序的抽帧秒数。区间尾为开区间，返回数量不超过帧预算。
     */
    public Set<Integer> select(
            File sourceVideo, int startSecond, int endSecond, int maximumFrames) {
        if (sourceVideo == null || !sourceVideo.isFile()
                || startSecond < 0 || endSecond <= startSecond
                || maximumFrames <= 0) {
            throw new IllegalArgumentException("invalid visual timestamp selection arguments");
        }
        List<FrameDifference> differences = scanDifferences(
                sourceVideo, startSecond, endSecond);
        Set<Integer> selected = new TreeSet<>();
        int duration = endSecond - startSecond;
        addEvenlySpaced(selected, startSecond, endSecond,
                Math.min(Math.min(UNIFORM_FRAME_BUDGET, maximumFrames), duration));
        differences.sort(Comparator.comparingDouble(
                FrameDifference::getScore).reversed());
        for (FrameDifference difference : differences) {
            selected.add(difference.getTimestampSecond());
            if (selected.size() >= Math.min(maximumFrames, duration)) {
                return selected;
            }
        }
        for (int second = startSecond;
             second < endSecond && selected.size() < Math.min(maximumFrames, duration);
             second++) {
            selected.add(second);
        }
        return selected;
    }

    private List<FrameDifference> scanDifferences(
            File sourceVideo, int startSecond, int endSecond) {
        List<FrameDifference> differences = new ArrayList<>();
        int[][] previousPixels = new int[1][];
        frameExtractor.streamRange(
                sourceVideo, startSecond, endSecond, SCAN_SCALE, frame -> {
                    int[] currentPixels = grayscalePixels(frame);
                    if (previousPixels[0] != null) {
                        differences.add(new FrameDifference(
                                frame.getTimestampSeconds(),
                                meanAbsoluteDifference(previousPixels[0], currentPixels)));
                    }
                    previousPixels[0] = currentPixels;
                });
        if (previousPixels[0] == null) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "visual change scan returned no frames: "
                            + sourceVideo.getAbsolutePath());
        }
        return differences;
    }

    private int[] grayscalePixels(InMemoryVideoFrame frame) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(frame.getJpegData())) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("unsupported image data");
            }
            int[] pixels = new int[image.getWidth() * image.getHeight()];
            int position = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int red = rgb >> 16 & 0xff;
                    int green = rgb >> 8 & 0xff;
                    int blue = rgb & 0xff;
                    pixels[position++] = (red * 30 + green * 59 + blue * 11) / 100;
                }
            }
            return pixels;
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot decode visual change scan frame at "
                            + frame.getTimestampSeconds() + "s", e);
        }
    }

    private double meanAbsoluteDifference(int[] previous, int[] current) {
        if (previous.length != current.length) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "visual change scan frame dimensions changed");
        }
        long difference = 0L;
        for (int index = 0; index < previous.length; index++) {
            difference += Math.abs(previous[index] - current[index]);
        }
        return difference / (double) previous.length;
    }

    private void addEvenlySpaced(
            Set<Integer> timestamps, int start, int end, int sampleCount) {
        if (sampleCount <= 1 || end - start <= 1) {
            timestamps.add(start);
            return;
        }
        int lastSecond = end - 1;
        for (int index = 0; index < sampleCount; index++) {
            double ratio = index / (double) (sampleCount - 1);
            timestamps.add(start + (int) Math.round((lastSecond - start) * ratio));
        }
    }

    private static final class FrameDifference {
        private final int timestampSecond;
        private final double score;

        private FrameDifference(int timestampSecond, double score) {
            this.timestampSecond = timestampSecond;
            this.score = score;
        }

        private int getTimestampSecond() {
            return timestampSecond;
        }

        private double getScore() {
            return score;
        }
    }
}
