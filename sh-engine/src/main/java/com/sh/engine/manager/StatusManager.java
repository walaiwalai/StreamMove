package com.sh.engine.manager;

import com.google.common.collect.Maps;
import com.sh.config.manager.CacheManager;
import com.sh.engine.base.StreamerInfoHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * 直播，投稿，录播的状态统一在这维护
 *
 * @author caiWen
 * @date 2023/1/24 16:28
 */
@Slf4j
@Component
public class StatusManager {
    @Resource
    private CacheManager cacheManager;

    /**
     * 直播间录像存放文件夹地址记录，按进行时间分段（当天日期）
     * key为streamer，value当前上传的所在目录文件
     */
    private static ConcurrentMap<String, String> recordStatusMap = Maps.newConcurrentMap();

    /**
     * 录播完的录像上传状态（文件按时间粒度划分），key为streamer，value录播文件地址
     */
    private static ConcurrentMap<String, String> uploadStatusMap = Maps.newConcurrentMap();

    /**
     * 当前录完的录像是否在被处理，key为录播文件地址，value当前处理的阶段
     */
    private static ConcurrentMap<String, String> postProcessMap = Maps.newConcurrentMap();


    public void printInfo() {
        log.info("There are {} streamers recoding, they are: ", recordStatusMap.keySet().size());
        for (Map.Entry<String, String> entry : recordStatusMap.entrySet()) {
            log.info("name: {}，path: {}", entry.getKey(), entry.getValue());
        }

        long postProcessCount = postProcessMap.keySet().size();
        log.info("There are {} streamers processing, they are: ", postProcessCount);
        for (Map.Entry<String, String> entry : postProcessMap.entrySet()) {
            log.info("path: {}, plugin: {}.", entry.getKey(), entry.getValue());
        }

        long uploadCount = uploadStatusMap.keySet().size();
        log.info("There are {} streamers uploading, they are: ", uploadCount);
        for (Map.Entry<String, String> entry : uploadStatusMap.entrySet()) {
            log.info("name: {}, path: {}", entry.getKey(), entry.getValue());
        }
    }

    /**
     * 录像是否在往某个平台投稿中
     *
     * @return 是否在投稿中
     */
    public boolean isRecordOnSubmission(String recordPath) {
        return uploadStatusMap.containsKey(recordPath);
    }

    /**
     * 锁住当前录像路径正在被某个平台投递
     *
     * @param recordPath 录像存储路径
     * @param platform   投稿平台
     */
    public void lockRecordForSubmission(String recordPath, String platform) {
        uploadStatusMap.put(recordPath, platform);
    }

    /**
     * 解锁当前时间段多个视频（001,002...）投稿状态
     */
    public void releaseRecordForSubmission(String pathWithTimeV) {
        uploadStatusMap.remove(pathWithTimeV);
    }

    public String getCurPlatform(String pathWithTimeV) {
        return uploadStatusMap.get(pathWithTimeV);
    }


    /**
     * 直播间录像是否正在被下载
     */
    public boolean isRoomPathFetchStream() {
        return recordStatusMap.containsKey(StreamerInfoHolder.getCurStreamerName());
    }

    public void addRoomPathStatus(String pathWithTimeV) {
        recordStatusMap.put(StreamerInfoHolder.getCurStreamerName(), pathWithTimeV);
    }

    public String getCurRecordPath() {
        return recordStatusMap.get(StreamerInfoHolder.getCurStreamerName());
    }

    public void deleteRoomPathStatus() {
        recordStatusMap.remove(StreamerInfoHolder.getCurStreamerName());
    }

    public Integer count() {
        // 录制和处理占用空间，上传不用
        return recordStatusMap.keySet().size() + +postProcessMap.keySet().size();
    }


    public void doPostProcess(String recordPath, String type) {
        postProcessMap.put(recordPath, type);
    }

    public String getCurPostProcessType(String recordPath) {
        return postProcessMap.get(recordPath);
    }

    public boolean isDoPostProcess(String recordPath) {
        return postProcessMap.containsKey(recordPath);
    }

    public void finishPostProcess(String recordPath) {
        postProcessMap.remove(recordPath);
    }


    public boolean isPathOccupied(String recordPath) {
        String onRecordPath = recordStatusMap.get(StreamerInfoHolder.getCurStreamerName());
        boolean isPathOnRecording = StringUtils.equals(recordPath, onRecordPath);

        return isRecordOnSubmission(recordPath) || isPathOnRecording || isDoPostProcess(recordPath);
    }
}
