package com.sh.engine.processor.plugin.lol;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 稀疏 KDA OCR 的持久化缓存。只保存有效结果，避免将临时 OCR 失败永久固化。
 */
@Data
class LolAdaptiveKdaCache {
    private int version = 1;
    private Map<String, VideoCache> videos = new HashMap<>();

    @Data
    static class VideoCache {
        private long fileSize;
        private long lastModified;
        private double durationSeconds;
        private Map<String, CachedKda> samples = new HashMap<>();

        boolean matches(long expectedFileSize, long expectedLastModified, double expectedDuration) {
            return fileSize == expectedFileSize
                    && lastModified == expectedLastModified
                    && Math.abs(durationSeconds - expectedDuration) < 0.001;
        }
    }

    @Data
    static class CachedKda {
        private boolean valid = true;
        private int kill;
        private int death;
        private int assist;

        public CachedKda() {
        }

        CachedKda(int kill, int death, int assist) {
            this.kill = kill;
            this.death = death;
            this.assist = assist;
        }

        static CachedKda invalid() {
            CachedKda cachedKda = new CachedKda();
            cachedKda.setValid(false);
            return cachedKda;
        }
    }
}
