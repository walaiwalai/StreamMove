package com.sh.config.model.storage;

import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.FileStoreUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author caiWen
 * @date 2023/1/25 22:51
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class FileStatusModel {
    /**
     * 直播流标题
     */
    private String streamTitle;

    /**
     * 各插件处理
     */
    private List<String> finishedPlugins = Lists.newArrayList();

    /**
     * 各平台上传完成情况
     */
    private List<String> finishedPlatforms = Lists.newArrayList();

    /**
     * 各平台首次投稿失败时间。用于失败文件保留和清理，不因后续重试而刷新。
     */
    private Map<String, Long> firstPostFailureTimeMap = Maps.newHashMap();
    
    /**
     * 视频元信息映射，键为视频文件名，值为视频相关信息
     */
    private Map<String, VideoMetaInfo> metaMap = Maps.newHashMap();

    /**
     * 上传视频情况
     * key是平台，value的key是视频名称，value是上传之后视频名称
     */
    private Map<String, Map<String, String>> uploadInfo = Maps.newHashMap();

    /**
     * 写到fileStatus.json
     */
    public void writeSelfToFile(String path) {
        FileStoreUtil.saveToFile(new File(path, "fileStatus.json"), this);
    }

    /**
     * 从fileStatus.json中读取
     *
     * @param path 所在文件目录
     * @return 录播文件状态
     */
    public static FileStatusModel loadFromFile(String path) {
        return FileStoreUtil.loadFromFile(new File(path, "fileStatus.json"), new TypeReference<FileStatusModel>() {
        });
    }

    public void finishUpload(String platform, String localVideoName, String remoteVideoName) {
        uploadInfo.computeIfAbsent(platform, k -> Maps.newHashMap());
        uploadInfo.get(platform).put(localVideoName, remoteVideoName);
    }

    public boolean isUpload(String platform, String localVideoName) {
        return uploadInfo.containsKey(platform) && uploadInfo.get(platform).containsKey(localVideoName);
    }

    public String fetchRemoteVideoName(String platform, String localVideoName) {
        if (!isUpload(platform, localVideoName)) {
            return null;
        }
        return uploadInfo.get(platform).get(localVideoName);
    }

    public boolean isFinishPost( String platform) {
        return finishedPlatforms.contains(platform);
    }

    public void finishPost(String platform) {
        finishedPlatforms.add(platform);
        if (firstPostFailureTimeMap != null) {
            firstPostFailureTimeMap.remove(platform);
        }
    }

    public void failPost(String platform) {
        failPost(platform, System.currentTimeMillis());
    }

    public void failPost(String platform, long failedAt) {
        if (firstPostFailureTimeMap == null) {
            firstPostFailureTimeMap = Maps.newHashMap();
        }
        firstPostFailureTimeMap.putIfAbsent(platform, failedAt);
    }

    public long getEarliestPostFailureTime(Collection<String> platforms) {
        if (firstPostFailureTimeMap == null || firstPostFailureTimeMap.isEmpty()
                || platforms == null || platforms.isEmpty()) {
            return 0L;
        }
        long earliest = Long.MAX_VALUE;
        for (String platform : platforms) {
            Long failedAt = firstPostFailureTimeMap.get(platform);
            if (failedAt != null && failedAt > 0) {
                earliest = Math.min(earliest, failedAt);
            }
        }
        return earliest == Long.MAX_VALUE ? 0L : earliest;
    }

    public boolean isFinishedPlugin(String pluginName) {
        return finishedPlugins.contains(pluginName);
    }

    public void finishPlugin(String pluginName) {
        finishedPlugins.add(pluginName);
    }
    
    /**
     * 视频元信息类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class VideoMetaInfo {
        /**
         * 视频时长（秒）
         */
        private int durationSecond;
        
        /**
         * 视频宽度
         */
        private int width;
        
        /**
         * 视频高度
         */
        private int height;
        
        /**
         * 记录开始时间戳
         */
        private long recordStartTimeStamp;
        
        /**
         * 记录结束时间戳
         */
        private long recordEndTimeStamp;
    }
}
