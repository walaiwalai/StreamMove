package com.sh.engine.manager;

import com.google.common.collect.Maps;
import com.sh.engine.model.record.Recorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

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
     * key存放视频文件夹地址，0为未有视频流往该文件夹写，1为有视频正在往当前文件夹写
     */
    private static Map<String, Integer> roomPathStatusMap = Maps.newHashMap();

    /**
     * 录播完的录像上传状态（文件按时间粒度划分），key为时间分段，0或null未被上传，1上传完成
     */
    private static Map<String, Integer> uploadStatusMap = Maps.newConcurrentMap();




    /**
     * 录像是否在投稿中(某个主播某段时间维度)
     * @param pathWithTimeV
     * @return
     */
    public boolean isRecordOnSubmission(String pathWithTimeV) {
        return Objects.equals(uploadStatusMap.get(pathWithTimeV), 1);
    }

    /**
     * 锁住当前时间段多个视频（001,002...）投稿状态
     * @param pathWithTimeV
     */
    public void lockRecordForSubmission(String pathWithTimeV) {
        uploadStatusMap.put(pathWithTimeV, 1);
    }

    /**
     * 解锁当前时间段多个视频（001,002...）投稿状态
     * @param pathWithTimeV
     */
    public void releaseRecordForSubmission(String pathWithTimeV) {
        uploadStatusMap.remove(pathWithTimeV);
    }




    /**
     * 当前主播是否在被录制
     * @return
     */
    public boolean isOnRecord(String streamerName) {
        return recorderMap.containsKey(streamerName);
    }

    /**
     * 根据当前主播名称获得recorder
     * @param streamerName
     * @return
     */
    public Recorder getRecorderByStreamerName(String streamerName) {
        return recorderMap.get(streamerName);
    }

    public void addRecorder(String streamerName, Recorder recorder) {
        recorderMap.put(streamerName, recorder);
    }

    public void deleteRecorder(String streamerName) {
        recorderMap.remove(streamerName);
    }




    /**
     * 该文件下直播间录像是否正在被下载
     * @param pathWithTimeV
     */
    public boolean isRoomPathFetchStream(String pathWithTimeV) {
        return Objects.equals(roomPathStatusMap.get(pathWithTimeV), 1);
    }

    public void addRoomPathStatus(String pathWithTimeV) {
        roomPathStatusMap.put(pathWithTimeV, 1);
    }

    public void deleteRoomPathStatus(String pathWithTimeV) {
        roomPathStatusMap.remove(pathWithTimeV);
    }


    /**
     * 主播房间相关操作：是否在线，上线，下线
     * @param streamerName
     * @return
     */
    public boolean isRoomOnline(String streamerName) {
        return roomStatusMap.containsKey(streamerName);
    }

    public void onlineRoom(String streamerName) {
        roomStatusMap.put(streamerName, 1);
    }

    public void offlineRoom(String streamerName) {
        roomStatusMap.remove(streamerName);
    }
}
