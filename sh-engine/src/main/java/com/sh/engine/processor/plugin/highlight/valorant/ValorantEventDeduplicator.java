package com.sh.engine.processor.plugin.highlight.valorant;

import org.apache.commons.lang3.StringUtils;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 在单个源视频内，根据 OCR 文字和击杀行图像指纹去除持续显示的重复事件。
 */
class ValorantEventDeduplicator {
    private static final int DUPLICATE_WINDOW_SECONDS = 5;
    private static final double MIN_TEXT_SIMILARITY = 0.70;
    private static final double MAX_HASH_DISTANCE = 0.35;
    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;

    private final List<RecentEvent> history = new ArrayList<>();

    /**
     * 同类型事件在 5 秒内文字相似或图像相似时视为击杀栏的持续显示。
     * 不同类型的击杀和死亡不会互相去重。
     */
    boolean isDuplicate(String eventType, double second, String text, long imageHash) {
        Iterator<RecentEvent> iterator = history.iterator();
        while (iterator.hasNext()) {
            if (second - iterator.next().second > DUPLICATE_WINDOW_SECONDS) {
                iterator.remove();
            }
        }

        String normalizedText = normalize(text);
        for (RecentEvent recent : history) {
            if (!recent.eventType.equals(eventType)) {
                continue;
            }
            boolean sameText = StringUtils.isNotBlank(normalizedText)
                    && textSimilarity(normalizedText, recent.normalizedText)
                    >= MIN_TEXT_SIMILARITY;
            boolean sameImage = hashDistance(imageHash, recent.imageHash)
                    <= MAX_HASH_DISTANCE;
            if (sameText || sameImage) {
                return true;
            }
        }

        history.add(new RecentEvent(eventType, second, normalizedText, imageHash));
        return false;
    }

    /**
     * 生成 64 位差异哈希；相邻灰度变化相同的击杀行会得到接近的指纹。
     */
    static long differenceHash(BufferedImage source) {
        BufferedImage resized = new BufferedImage(
                HASH_WIDTH, HASH_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, HASH_WIDTH, HASH_HEIGHT, null);
        } finally {
            graphics.dispose();
        }

        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++) {
                int left = resized.getRaster().getSample(x, y, 0);
                int right = resized.getRaster().getSample(x + 1, y, 0);
                if (left > right) {
                    hash |= 1L << bit;
                }
                bit++;
            }
        }
        return hash;
    }

    private static double hashDistance(long first, long second) {
        return Long.bitCount(first ^ second) / 64.0;
    }

    private static double textSimilarity(String first, String second) {
        if (first.equals(second)) {
            return 1.0;
        }
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }
        int[] previous = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int replaceCost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + replaceCost);
            }
            previous = current;
        }
        return 1.0 - previous[second.length()] / (double) Math.max(first.length(), second.length());
    }

    private String normalize(String text) {
        return StringUtils.defaultString(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "");
    }

    private static class RecentEvent {
        private final String eventType;
        private final double second;
        private final String normalizedText;
        private final long imageHash;

        private RecentEvent(String eventType,
                            double second,
                            String normalizedText,
                            long imageHash) {
            this.eventType = eventType;
            this.second = second;
            this.normalizedText = normalizedText;
            this.imageHash = imageHash;
        }
    }
}
