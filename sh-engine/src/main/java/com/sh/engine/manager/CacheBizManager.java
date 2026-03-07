package com.sh.engine.manager;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CacheBizManager {
    @Resource
    private CacheManager cacheManager;

    public boolean isCertainVideoFinished(String streamerName, String videoId) {
        String key = "certain_keys_" + streamerName;
        String finishFlag = cacheManager.getHash(key, videoId, new TypeReference<String>() {
        });
        return StringUtils.isNotBlank(finishFlag);
    }

    public void finishCertainVideo(String streamerName, String videoId) {
        String key = "certain_keys_" + streamerName;
        cacheManager.setHash(key, videoId, "1", 2, TimeUnit.DAYS);
    }

    /**
     * 获取等待 1080p 的开始时间（毫秒）
     * @param streamerName 主播名
     * @param videoId 视频ID（用 recordedAt 作为唯一标识）
     * @return 开始等待的时间戳，如果没有则在等待返回 null
     */
    public Long getWaitingFor1080StartTime(String streamerName, String videoId) {
        String key = "wait_1080_" + streamerName;
        String value = cacheManager.getHash(key, videoId, new TypeReference<String>() {});
        if (StringUtils.isNotBlank(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 设置等待 1080p 的标记
     * @param streamerName 主播名
     * @param videoId 视频ID
     * @param startTime 等待开始时间戳（毫秒）
     */
    public void setWaitingFor1080(String streamerName, String videoId, long startTime) {
        String key = "wait_1080_" + streamerName;
        // 缓存 2 小时（足够覆盖 30 分钟等待期）
        cacheManager.setHash(key, videoId, String.valueOf(startTime), 2, TimeUnit.HOURS);
    }

    /**
     * 清除等待 1080p 的标记
     * @param streamerName 主播名
     * @param videoId 视频ID
     */
    public void clearWaitingFor1080(String streamerName, String videoId) {
        String key = "wait_1080_" + streamerName;
        cacheManager.deleteHashTag(key, videoId);
    }
}
