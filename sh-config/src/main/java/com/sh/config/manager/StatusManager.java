package com.sh.config.manager;

import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

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


    public String printInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nThere are ").append(recordStatusMap.keySet().size()).append(" streamers recording, they are: ");
        for (Map.Entry<String, String> entry : recordStatusMap.entrySet()) {
            sb.append("\nname: ").append(entry.getKey()).append(", path: ").append(entry.getValue());
        }
        sb.append("\nThere are ").append(uploadStatusMap.keySet().size()).append(" streamers uploading, they are: ");
        for (Map.Entry<String, String> entry : uploadStatusMap.entrySet()) {
            sb.append("\nname: ").append(entry.getKey()).append(", path: ").append(entry.getValue());
        }
        sb.append("\nThere are ").append(postProcessMap.keySet().size()).append(" streamers processing, they are: ");
        for (Map.Entry<String, String> entry : postProcessMap.entrySet()) {
            sb.append("\npath: ").append(entry.getKey()).append(", plugin: ").append(entry.getValue());
        }
        sb.append("\n");
        return sb.toString();
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
    public boolean isRoomPathFetchStream(String streamerName) {
        return recordStatusMap.containsKey(streamerName);
    }

    public void addRoomPathStatus(String pathWithTimeV, String streamerName) {
        recordStatusMap.put(streamerName, pathWithTimeV);
    }

    public void deleteRoomPathStatus(String streamerName) {
        recordStatusMap.remove(streamerName);
    }

    public Integer count() {
        return recordStatusMap.keySet().size();
    }


    public void doPostProcess(String recordPath, String type) {
        postProcessMap.put(recordPath, type);
    }

    public String getCurPostProcessType(String recordPath) {
        return postProcessMap.get(recordPath);
    }

    public void finishPostProcess(String recordPath) {
        postProcessMap.remove(recordPath);
    }


    public boolean isPathOccupied(String recordPath, String streamerName) {
        String onRecordPath = recordStatusMap.get(streamerName);
        boolean isPathOnRecording = StringUtils.equals(recordPath, onRecordPath);

        return uploadStatusMap.containsKey(recordPath) || isPathOnRecording || postProcessMap.containsKey(recordPath);
    }
}
