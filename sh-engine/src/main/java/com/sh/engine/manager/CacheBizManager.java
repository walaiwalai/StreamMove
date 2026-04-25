package com.sh.engine.manager;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
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

    /**
     * 获取 AI 高光分析缓存结果
     * @param streamerName 主播名
     * @param segmentKey 时间段标识（如 "5600-5670"）
     */
    public HighlightAnalysisResult getHighlightAnalysis(String streamerName, String segmentKey) {
        String key = "hl_analysis_" + streamerName;
        return cacheManager.getHash(key, segmentKey, new TypeReference<HighlightAnalysisResult>() {});
    }

    /**
     * 缓存 AI 高光分析结果（7天过期）
     */
    public void saveHighlightAnalysis(String streamerName, String segmentKey, HighlightAnalysisResult result) {
        String key = "hl_analysis_" + streamerName;
        cacheManager.setHash(key, segmentKey, result, 7, TimeUnit.DAYS);
    }

    /**
     * 获取 ASR 转写缓存结果
     */
    public List<AsrSegment> getAsrResult(String streamerName, String segmentKey) {
        String key = "asr_" + streamerName;
        return cacheManager.getHash(key, segmentKey, new TypeReference<List<AsrSegment>>() {});
    }

    /**
     * 缓存 ASR 转写结果（7天过期）
     */
    public void saveAsrResult(String streamerName,
                              String segmentKey, List<AsrSegment> segments) {
        String key = "asr_" + streamerName;
        cacheManager.setHash(key, segmentKey, segments, 7, TimeUnit.DAYS);
    }
}
