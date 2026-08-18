package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.event.StreamRecordEndEvent;
import com.sh.engine.event.StreamRecordStartEvent;
import com.sh.engine.manager.CacheBizManager;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamUrlStreamRecorder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Streamrecorder.io 平台检查器
 * @Author caiwen
 * @Date 2025 08 14 00 06
 **/
@Component
@Slf4j
public class StreamrecorderIOChecker extends AbstractRoomChecker {

    /**
     * 长视频阈值：14小时（秒），超过此时长使用720p下载
     */
    private static final int LONG_VIDEO_THRESHOLD_SECONDS = 20 * 60 * 60;

    @Resource
    private CacheBizManager cacheBizManager;
    @Resource
    private StreamrecorderIOLoginManager loginManager;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String[] split = roomUrl.split("/");
        String targetId = split[split.length - 1];

        // 发送请求获取录制信息
        String resp = fetchRecordings(targetId, streamerConfig);

        JSONObject respObj = JSON.parseObject(resp);
        boolean isCertainVod = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls());
        if (isCertainVod) {
            return fetchCertainRecords(streamerConfig, respObj);
        } else {
            return fetchLatestRecord(streamerConfig, respObj);
        }
    }

    private String fetchRecordings(String targetId, StreamerConfig streamerConfig) {
        int limit = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls()) ? 100 : 5;
        String url = String.format("https://streamrecorder.io/api/user/recordingsv2?targetid=%s&offset=0&limit=%d", targetId, limit);

        return loginManager.executeWithCookies(cookieString -> {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", cookieString)
                    .build();
            return OkHttpClientUtil.execute(request);
        });
    }

    private StreamRecorder fetchCertainRecords(StreamerConfig streamerConfig, JSONObject respObj) {
        String videoId = null;
        for (String vid : streamerConfig.getCertainVodUrls()) {
            boolean isFinished = cacheBizManager.isCertainVideoFinished(streamerConfig.getName(), vid);
            if (!isFinished) {
                videoId = vid;
                break;
            }
        }
        if (videoId == null) {
            return null;
        }

        String downloadLink = null;
        Date recordedAt = null;
        for (Object data : respObj.getJSONArray("data")) {
            JSONObject dataObj = (JSONObject) data;
            String id = String.valueOf(dataObj.getLong("id"));
            if (StringUtils.equals(id, videoId)) {
                recordedAt = parseGMT8Date(dataObj.getString("recorded_at"));
                int duration = dataObj.getIntValue("duration");
                if (duration > LONG_VIDEO_THRESHOLD_SECONDS) {
                    downloadLink = getSourceLink(dataObj, 720);
                    log.info("Certain vod long video detected ({}s > {}s), using 720p: {}", duration, LONG_VIDEO_THRESHOLD_SECONDS, videoId);
                } else {
                    downloadLink = getSourceLink(dataObj, 1080);
                    if (downloadLink == null) {
                        downloadLink = dataObj.getJSONArray("sources").getJSONObject(0).getString("downloadlink");
                    }
                }
                break;
            }
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("finishField", videoId);

        return new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink, extra);
    }

    /**
     * 等待 1080p 的超时时间（30分钟）
     */
    private static final long WAIT_FOR_1080_TIMEOUT_MS = 30 * 60 * 1000;

    private StreamRecorder fetchLatestRecord(StreamerConfig streamerConfig, JSONObject respObj) {
        JSONArray dataArr = respObj.getJSONArray("data");
        if (CollectionUtils.isEmpty(dataArr)) {
            return null;
        }
        String name = streamerConfig.getName();
        JSONObject latestRecord = dataArr.getJSONObject(0);
        String status = latestRecord.getString("status");
        Date latestRecordedAt = parseGMT8Date(latestRecord.getString("recorded_at"));
        log.info("streamer io check, status: {}, lastRecordAt: {}", status, latestRecordedAt);

        if (StringUtils.equals(status, "running")) {
            StreamRecordStartEvent event = new StreamRecordStartEvent(this, name, latestRecordedAt);
            eventPublisher.publishEvent(event);
            return null;
        } else if (StringUtils.equals(status, "finished")) {
            int recordIndex = findNextRecordIndex(streamerConfig, dataArr);
            if (recordIndex < 0) {
                return null;
            }

            JSONObject record = dataArr.getJSONObject(recordIndex);
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            String streamTitle = record.getString("streamtitle");

            StreamRecordEndEvent event = new StreamRecordEndEvent(this, name);
            eventPublisher.publishEvent(event);

            // 解析 sources，获取最佳下载链接（优先 1080p）
            Map<String, String> extra = new HashMap<>();
            extra.put("streamTitle", streamTitle);

            String downloadLink = resolveDownloadLink(streamerConfig, record, recordedAt);
            return downloadLink == null ? null : new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink, extra);
        }
        return null;
    }

    /**
     * 同一场直播判定的 buffer 时间（秒）：考虑到平台分段、断流重连等情况，留 5 分钟容差
     */
    private static final int DUPLICATE_BUFFER_SECONDS = 5 * 60;

    /**
     * 从最旧的记录开始，将连续重叠的记录归为同一组。
     * 未处理的组只返回时长最长的记录；若组内已有处理过的记录，则整组跳过。
     */
    private int findNextRecordIndex(StreamerConfig streamerConfig, JSONArray dataArr) {
        OverlappingRecordGroup group = new OverlappingRecordGroup();
        for (int i = dataArr.size() - 1; i >= 0; i--) {
            JSONObject record = dataArr.getJSONObject(i);
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (recordedAt == null) {
                continue;
            }

            if (!group.isEmpty() && !group.overlaps(recordedAt)) {
                int nextRecordIndex = group.getNextRecordIndex();
                if (nextRecordIndex >= 0) {
                    return nextRecordIndex;
                }
                group = new OverlappingRecordGroup();
            }

            group.add(i, recordedAt, record.getIntValue("duration"),
                    checkVodIsNew(streamerConfig, recordedAt));
        }
        return group.getNextRecordIndex();
    }

    private static final class OverlappingRecordGroup {

        private long endWithBufferMs = Long.MIN_VALUE;
        private boolean containsHandledRecord;
        private int longestPendingIndex = -1;
        private int longestPendingDuration = -1;

        private boolean isEmpty() {
            return endWithBufferMs == Long.MIN_VALUE;
        }

        private boolean overlaps(Date recordedAt) {
            return recordedAt.getTime() <= endWithBufferMs;
        }

        private void add(int index, Date recordedAt, int duration, boolean pending) {
            long recordEndWithBufferMs = recordedAt.getTime();
            if (duration > 0) {
                recordEndWithBufferMs += ((long) duration + DUPLICATE_BUFFER_SECONDS) * 1000L;
            }
            endWithBufferMs = Math.max(endWithBufferMs, recordEndWithBufferMs);

            if (!pending) {
                containsHandledRecord = true;
            } else if (!containsHandledRecord && duration > longestPendingDuration) {
                longestPendingIndex = index;
                longestPendingDuration = duration;
            }
        }

        private int getNextRecordIndex() {
            return containsHandledRecord ? -1 : longestPendingIndex;
        }
    }

    /**
     * 当前最高可用分辨率达到 1080 时立即下载；否则等待 30 分钟后下载最高可用分辨率。
     */
    private String resolveDownloadLink(StreamerConfig streamerConfig, JSONObject record, Date recordedAt) {
        String videoId = String.valueOf(recordedAt.getTime());
        String streamerName = streamerConfig.getName();
        JSONObject highestSource = getHighestResolutionSource(record);
        int highestResolution = highestSource == null ? 0 : highestSource.getIntValue("resolution");

        if (highestResolution >= 1080) {
            cacheBizManager.clearWaitingFor1080(streamerName, videoId);
            log.info("Found {}p source, downloading: {}", highestResolution, streamerName);
            return highestSource.getString("downloadlink");
        }

        Long waitStartTime = cacheBizManager.getWaitingFor1080StartTime(streamerName, videoId);
        if (waitStartTime == null) {
            cacheBizManager.setWaitingFor1080(streamerName, videoId, System.currentTimeMillis());
            log.info("Highest source is {}p, starting 30min wait for 1080p: {}", highestResolution, streamerName);
            return null;
        }

        long elapsed = System.currentTimeMillis() - waitStartTime;
        if (elapsed < WAIT_FOR_1080_TIMEOUT_MS) {
            long remaining = (WAIT_FOR_1080_TIMEOUT_MS - elapsed) / 1000 / 60;
            log.info("Highest source is {}p, still waiting for 1080p, {} min remaining: {}",
                    highestResolution, remaining, streamerName);
            return null;
        }

        if (highestSource == null) {
            log.warn("Wait timeout but no downloadable source is available, continuing to wait: {}", streamerName);
            return null;
        }

        cacheBizManager.clearWaitingFor1080(streamerName, videoId);
        log.info("Wait timeout, downloading highest available {}p source: {}", highestResolution, streamerName);
        return highestSource.getString("downloadlink");
    }

    /**
     * 只在 downloadlink 非空的 source 中选择最高分辨率。
     */
    private JSONObject getHighestResolutionSource(JSONObject record) {
        JSONArray sources = record.getJSONArray("sources");
        if (CollectionUtils.isEmpty(sources)) {
            return null;
        }
        JSONObject highestSource = null;
        for (int i = 0; i < sources.size(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (StringUtils.isBlank(source.getString("downloadlink"))) {
                continue;
            }
            if (highestSource == null
                    || source.getIntValue("resolution") > highestSource.getIntValue("resolution")) {
                highestSource = source;
            }
        }
        return highestSource;
    }

    /**
     * 从 sources 中获取指定分辨率的下载链接
     */
    private String getSourceLink(JSONObject latestRecord, int resolution) {
        JSONArray sources = latestRecord.getJSONArray("sources");
        if (CollectionUtils.isEmpty(sources)) {
            return null;
        }
        for (int i = 0; i < sources.size(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (source.getIntValue("resolution") == resolution) {
                return source.getString("downloadlink");
            }
        }
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.STREAM_RECORDER_IO;
    }

    /**
     * 解析 GMT+8 时间
     */
    private Date parseGMT8Date(String dateStr) {
        Date recordedAt = null;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        try {
            recordedAt = DateUtils.addHours(sdf.parse(dateStr), 8);
        } catch (Exception e) {
            log.error("parse date failed, dateStr: {}", dateStr, e);
        }
        return recordedAt;
    }

}
