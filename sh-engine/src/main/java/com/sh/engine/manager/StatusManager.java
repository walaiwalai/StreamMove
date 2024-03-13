package com.sh.engine.manager;

import com.google.common.collect.Maps;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.record.Recorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 直播，投稿，录播的状态统一在这维护
 * @author caiWen
 * @date 2023/1/24 16:28
 */
@Slf4j
@Component
public class StatusManager {
    /**
     * 主播的直播是否在被录播，key直播人名字，0不在录播，1在录播
     */
    private static Map<String, Recorder> recorderMap = Maps.newConcurrentMap();

    /**
     * 主播直播间是否在线，key直播人名字，0不在线，1在线
     */
    private static Map<String, Integer> roomStatusMap = Maps.newHashMap();

    /**
     * 直播间录像存放文件夹地址记录，按进行时间分段（当天日期）
     * key为streamer，value当前上传的所在目录文件
     */
    private static Map<String, String> roomPathStatusMap = Maps.newConcurrentMap();

    /**
     * 录播完的录像上传状态（文件按时间粒度划分），key为streamer，value录播文件地址
     */
    private static Map<String, String> uploadStatusMap = Maps.newConcurrentMap();

    /**
     * 当前录完的录像是否在被处理，key为录播文件地址，value当前处理的阶段
     */
    private static Map<String, String> postProcessMap = Maps.newConcurrentMap();


    public void printInfo() {
        log.info("There are {} streamers recoding, they are: ", roomPathStatusMap.keySet().size());
        for (Map.Entry<String, String> entry : roomPathStatusMap.entrySet()) {
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
     * 录像是否在投稿中(某个主播某段时间维度)
     * @return
     */
    public boolean isRecordOnSubmission(String recordPath) {
        return uploadStatusMap.containsKey(recordPath);
    }

    /**
     * 锁住当前时间段多个视频（001,002...）投稿状态
     *
     * @param pathWithTimeV
     */
    public void lockRecordForSubmission(String pathWithTimeV, String platform) {
        uploadStatusMap.put(pathWithTimeV, platform);
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


//    /**
//     * 当前主播是否在被录制
//     *
//     * @return
//     */
//    public boolean isOnRecord() {
//        return recorderMap.containsKey(StreamerInfoHolder.getCurStreamerName());
//    }
//
//    public void addRecorder(String streamerName, Recorder recorder) {
//        recorderMap.put(streamerName, recorder);
//    }
//
//    public void deleteRecorder(String streamerName) {
//        recorderMap.remove(streamerName);
//    }

    /**
     * 直播间录像是否正在被下载
     */
    public boolean isRoomPathFetchStream() {
        return roomPathStatusMap.containsKey(StreamerInfoHolder.getCurStreamerName());
    }

    public void addRoomPathStatus(String pathWithTimeV) {
        roomPathStatusMap.put(StreamerInfoHolder.getCurStreamerName(), pathWithTimeV);
    }

    public String getCurRecordPath() {
        return roomPathStatusMap.get(StreamerInfoHolder.getCurStreamerName());
    }

    public void deleteRoomPathStatus() {
        roomPathStatusMap.remove(StreamerInfoHolder.getCurStreamerName());
    }

    public Integer countOnRecord() {
        return roomPathStatusMap.keySet().size();
    }


//    /**
//     * 主播房间相关操作：是否在线，上线，下线
//     * @param streamerName
//     * @return
//     */
//    public boolean isRoomOnline(String streamerName) {
//        return roomStatusMap.containsKey(streamerName);
//    }
//
//    public void onlineRoom(String streamerName) {
//        roomStatusMap.put(streamerName, 1);
//    }
//
//    public void offlineRoom(String streamerName) {
//        roomStatusMap.remove(streamerName);
//    }

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
        String onRecordPath = roomPathStatusMap.get(StreamerInfoHolder.getCurStreamerName());
        boolean isPathOnRecording = StringUtils.equals(recordPath, onRecordPath);

        return isRecordOnSubmission(recordPath) || isPathOnRecording || isDoPostProcess(recordPath);
    }
}
