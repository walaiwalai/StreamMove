package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.HighlightMask;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 将被判定为广告的 OCR 文本框合并，并利用局部画面梯度扩展到广告底板边界。
 */
@Component
public class AdvertisementRegionResolver {
    private static final double HORIZONTAL_GROUP_GAP_RATIO = 0.03;
    private static final double VERTICAL_GROUP_GAP_RATIO = 0.025;
    private static final double HORIZONTAL_SEARCH_RATIO = 0.08;
    private static final double VERTICAL_SEARCH_RATIO = 0.08;
    private static final double MIN_HORIZONTAL_PADDING_RATIO = 0.008;
    private static final double MIN_VERTICAL_PADDING_RATIO = 0.008;
    private static final double MIN_EDGE_SCORE = 16.0;
    private static final double MAX_REGION_AREA_RATIO = 0.20;
    private static final int SAFETY_PADDING_PIXELS = 4;

    /**
     * 合并单帧内相邻广告文字框，并将范围扩展到附近视觉边界。
     *
     * @param image     OCR 对应的完整视频帧
     * @param textBoxes 被大模型判定为广告的 OCR 文本框
     * @return 当前帧内一个或多个广告矩形
     */
    public List<Rectangle> resolveFrame(BufferedImage image, List<Rectangle> textBoxes) {
        List<Rectangle> grouped = mergeNearby(
                textBoxes, image.getWidth(), image.getHeight());
        List<Rectangle> resolved = new ArrayList<>();
        for (Rectangle group : grouped) {
            resolved.add(expandToBoundary(image, group));
        }
        return resolved;
    }

    /**
     * 合并多帧中位置相近的广告区域，并转换为可跨分片使用的归一化计划。
     *
     * @param regions    多个采样帧解析出的矩形区域
     * @param frameWidth 源画面宽度
     * @param frameHeight 源画面高度
     * @return 整场直播共用的蒙层计划
     */
    public HighlightMaskPlan combineFrames(List<Rectangle> regions,
                                           int frameWidth,
                                           int frameHeight) {
        if (regions == null || regions.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return HighlightMaskPlan.empty();
        }
        List<Rectangle> merged = mergeNearby(regions, frameWidth, frameHeight);
        List<HighlightMask> masks = new ArrayList<>();
        for (Rectangle region : merged) {
            Rectangle clipped = clip(region, frameWidth, frameHeight);
            masks.add(new HighlightMask(
                    clipped.getX() / frameWidth,
                    clipped.getY() / frameHeight,
                    clipped.getWidth() / frameWidth,
                    clipped.getHeight() / frameHeight));
        }
        return new HighlightMaskPlan(masks);
    }

    /**
     * 反复合并横向或纵向距离较近的矩形，避免同一广告底板产生多个小蒙层。
     */
    private List<Rectangle> mergeNearby(List<Rectangle> source,
                                        int frameWidth,
                                        int frameHeight) {
        List<Rectangle> merged = new ArrayList<>();
        if (source == null) {
            return merged;
        }
        int horizontalGap = Math.max(8, (int) Math.round(frameWidth * HORIZONTAL_GROUP_GAP_RATIO));
        int verticalGap = Math.max(12, (int) Math.round(frameHeight * VERTICAL_GROUP_GAP_RATIO));
        for (Rectangle item : source) {
            if (item == null || item.width <= 0 || item.height <= 0) {
                continue;
            }
            Rectangle current = clip(item, frameWidth, frameHeight);
            boolean changed;
            do {
                changed = false;
                for (int i = merged.size() - 1; i >= 0; i--) {
                    Rectangle existing = merged.get(i);
                    if (isNearby(existing, current, horizontalGap, verticalGap)) {
                        current = existing.union(current);
                        merged.remove(i);
                        changed = true;
                    }
                }
            } while (changed);
            merged.add(current);
        }
        merged.sort(Comparator.comparingInt((Rectangle item) -> item.y)
                .thenComparingInt(item -> item.x));
        return merged;
    }

    /**
     * 仅在两个框的垂直投影或水平投影接近时合并，避免斜对角广告被连成大块。
     */
    private boolean isNearby(Rectangle first,
                             Rectangle second,
                             int horizontalGap,
                             int verticalGap) {
        int horizontalDistance = Math.max(0,
                Math.max(first.x, second.x) - Math.min(first.x + first.width, second.x + second.width));
        int verticalDistance = Math.max(0,
                Math.max(first.y, second.y) - Math.min(first.y + first.height, second.y + second.height));
        boolean verticalProjectionNear = verticalDistance <= verticalGap;
        boolean horizontalProjectionNear = horizontalDistance <= horizontalGap;
        return (horizontalDistance == 0 && verticalProjectionNear)
                || (verticalDistance == 0 && horizontalProjectionNear)
                || (horizontalProjectionNear && verticalProjectionNear
                && horizontalDistance + verticalDistance <= Math.max(horizontalGap, verticalGap));
    }

    /**
     * 在文字框外侧有限范围内查找平均颜色梯度最强的行列，作为广告底板边界。
     */
    private Rectangle expandToBoundary(BufferedImage image, Rectangle seed) {
        int frameWidth = image.getWidth();
        int frameHeight = image.getHeight();
        int minimumXPadding = Math.max(8,
                (int) Math.round(frameWidth * MIN_HORIZONTAL_PADDING_RATIO));
        int minimumYPadding = Math.max(6,
                (int) Math.round(frameHeight * MIN_VERTICAL_PADDING_RATIO));
        Rectangle padded = clip(new Rectangle(
                seed.x - minimumXPadding,
                seed.y - minimumYPadding,
                seed.width + minimumXPadding * 2,
                seed.height + minimumYPadding * 2), frameWidth, frameHeight);

        int maxXSearch = Math.max(minimumXPadding,
                (int) Math.round(frameWidth * HORIZONTAL_SEARCH_RATIO));
        int maxYSearch = Math.max(minimumYPadding,
                (int) Math.round(frameHeight * VERTICAL_SEARCH_RATIO));
        int left = strongestVerticalEdge(image,
                Math.max(1, seed.x - maxXSearch), Math.max(1, seed.x - minimumXPadding),
                padded.y, padded.y + padded.height, padded.x);
        int rightEdge = strongestVerticalEdge(image,
                Math.min(frameWidth - 1, seed.x + seed.width + minimumXPadding),
                Math.min(frameWidth - 1, seed.x + seed.width + maxXSearch),
                padded.y, padded.y + padded.height, padded.x + padded.width);
        int top = strongestHorizontalEdge(image,
                Math.max(1, seed.y - maxYSearch), Math.max(1, seed.y - minimumYPadding),
                left, rightEdge, padded.y);
        int bottomEdge = strongestHorizontalEdge(image,
                Math.min(frameHeight - 1, seed.y + seed.height + minimumYPadding),
                Math.min(frameHeight - 1, seed.y + seed.height + maxYSearch),
                left, rightEdge, padded.y + padded.height);

        Rectangle expanded = clip(new Rectangle(
                left - SAFETY_PADDING_PIXELS,
                top - SAFETY_PADDING_PIXELS,
                rightEdge - left + SAFETY_PADDING_PIXELS * 2,
                bottomEdge - top + SAFETY_PADDING_PIXELS * 2), frameWidth, frameHeight);
        double areaRatio = expanded.getWidth() * expanded.getHeight()
                / (frameWidth * (double) frameHeight);
        return areaRatio <= MAX_REGION_AREA_RATIO ? expanded : padded;
    }

    /**
     * 在限定横向窗口内选择平均颜色变化最明显的竖线。
     */
    private int strongestVerticalEdge(BufferedImage image,
                                      int fromX,
                                      int toX,
                                      int fromY,
                                      int toY,
                                      int fallback) {
        double bestScore = 0;
        int bestX = fallback;
        for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) {
            double score = verticalEdgeScore(image, x, fromY, toY);
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
            }
        }
        return bestScore >= MIN_EDGE_SCORE ? bestX : fallback;
    }

    /**
     * 在限定纵向窗口内选择平均颜色变化最明显的横线。
     */
    private int strongestHorizontalEdge(BufferedImage image,
                                        int fromY,
                                        int toY,
                                        int fromX,
                                        int toX,
                                        int fallback) {
        double bestScore = 0;
        int bestY = fallback;
        for (int y = Math.min(fromY, toY); y <= Math.max(fromY, toY); y++) {
            double score = horizontalEdgeScore(image, y, fromX, toX);
            if (score > bestScore) {
                bestScore = score;
                bestY = y;
            }
        }
        return bestScore >= MIN_EDGE_SCORE ? bestY : fallback;
    }

    /**
     * 计算相邻两列像素的平均 RGB 差异。
     */
    private double verticalEdgeScore(BufferedImage image, int x, int fromY, int toY) {
        int startY = Math.max(0, fromY);
        int endY = Math.min(image.getHeight(), toY);
        long total = 0;
        int samples = 0;
        for (int y = startY; y < endY; y += 2) {
            total += colorDistance(image.getRGB(x, y), image.getRGB(x - 1, y));
            samples++;
        }
        return samples == 0 ? 0 : total / (samples * 3.0);
    }

    /**
     * 计算相邻两行像素的平均 RGB 差异。
     */
    private double horizontalEdgeScore(BufferedImage image, int y, int fromX, int toX) {
        int startX = Math.max(0, fromX);
        int endX = Math.min(image.getWidth(), toX);
        long total = 0;
        int samples = 0;
        for (int x = startX; x < endX; x += 2) {
            total += colorDistance(image.getRGB(x, y), image.getRGB(x, y - 1));
            samples++;
        }
        return samples == 0 ? 0 : total / (samples * 3.0);
    }

    private int colorDistance(int first, int second) {
        return Math.abs((first >> 16 & 0xff) - (second >> 16 & 0xff))
                + Math.abs((first >> 8 & 0xff) - (second >> 8 & 0xff))
                + Math.abs((first & 0xff) - (second & 0xff));
    }

    private Rectangle clip(Rectangle source, int frameWidth, int frameHeight) {
        int x = Math.max(0, Math.min(source.x, frameWidth - 1));
        int y = Math.max(0, Math.min(source.y, frameHeight - 1));
        int right = Math.max(x + 1, Math.min(source.x + source.width, frameWidth));
        int bottom = Math.max(y + 1, Math.min(source.y + source.height, frameHeight));
        return new Rectangle(x, y, right - x, bottom - y);
    }
}
