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
        int limit = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls()) ? 100 : 10;
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

        return new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(),
                getType().getType(), downloadLink, extra, true);
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
        Map<String, JSONObject> recordsById = new HashMap<>();
        List<JSONObject> runningRecords = new ArrayList<>();
        List<JSONObject> finishedRecords = new ArrayList<>();
        for (int i = 0; i < dataArr.size(); i++) {
            JSONObject record = dataArr.getJSONObject(i);
            String videoId = record.getString("id");
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (StringUtils.isBlank(videoId) || recordedAt == null) {
                continue;
            }

            recordsById.put(videoId, record);
            if (StringUtils.equals("running", record.getString("status"))) {
                runningRecords.add(record);
            } else if (StringUtils.equals("finished", record.getString("status"))) {
                finishedRecords.add(record);
            }
        }

        Set<String> cachedVideoIds = new HashSet<>(cacheBizManager.getStreamrecorderRunningIds(name));
        List<JSONObject> cachedRecords = new ArrayList<>();
        for (String videoId : cachedVideoIds) {
            JSONObject record = recordsById.get(videoId);
            if (record == null) {
                log.warn("Streamrecorder cached video is missing, clearing cache: {}, id: {}", name, videoId);
                cachedVideoIds.clear();
                cachedRecords.clear();
                cacheBizManager.clearStreamrecorderRunningIds(name);
                break;
            }
            cachedRecords.add(record);
        }

        // 下载成功后 lastRecordTime 会等于组内最大 recorded_at，下一轮在这里清理缓存。
        Date cachedLatestAt = null;
        for (JSONObject record : cachedRecords) {
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (cachedLatestAt == null || recordedAt.after(cachedLatestAt)) {
                cachedLatestAt = recordedAt;
            }
        }
        if (cachedLatestAt != null && !checkVodIsNew(streamerConfig, cachedLatestAt)) {
            cachedVideoIds.clear();
            cachedRecords.clear();
            cacheBizManager.clearStreamrecorderRunningIds(name);
        }

        // 直播进行中：把当前所有新 videoId 合并到同一个缓存集合。
        if (CollectionUtils.isNotEmpty(runningRecords)) {
            Date latestRunningAt = null;
            for (JSONObject record : runningRecords) {
                Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
                if (checkVodIsNew(streamerConfig, recordedAt)) {
                    cachedVideoIds.add(record.getString("id"));
                }
                if (latestRunningAt == null || recordedAt.after(latestRunningAt)) {
                    latestRunningAt = recordedAt;
                }
            }
            cacheBizManager.saveStreamrecorderRunningIds(name, cachedVideoIds);
            eventPublisher.publishEvent(new StreamRecordStartEvent(this, name, latestRunningAt));
            return null;
        }

        // 缓存中的录像全部结束后，最长录像提供下载链接，最大 recorded_at 作为 regDate。
        if (CollectionUtils.isNotEmpty(cachedRecords)) {
            boolean allFinished = cachedRecords.stream()
                    .allMatch(record -> StringUtils.equals("finished", record.getString("status")));
            if (!allFinished) {
                return null;
            }

            JSONObject longestRecord = cachedRecords.get(0);
            Date latestRecordedAt = parseGMT8Date(longestRecord.getString("recorded_at"));
            for (JSONObject record : cachedRecords) {
                Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
                if (record.getIntValue("duration") > longestRecord.getIntValue("duration")) {
                    longestRecord = record;
                }
                if (recordedAt.after(latestRecordedAt)) {
                    latestRecordedAt = recordedAt;
                }
            }
            return buildStreamRecorder(streamerConfig, longestRecord, latestRecordedAt);
        }

        // 没有 running 缓存时，只下载最新的 finished 录像，不补录更早记录。
        JSONObject latestFinishedRecord = null;
        Date latestRecordedAt = null;
        for (JSONObject record : finishedRecords) {
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (checkVodIsNew(streamerConfig, recordedAt)
                    && (latestRecordedAt == null || recordedAt.after(latestRecordedAt))) {
                latestFinishedRecord = record;
                latestRecordedAt = recordedAt;
            }
        }
        if (latestFinishedRecord == null) {
            return null;
        }
        return buildStreamRecorder(streamerConfig, latestFinishedRecord, latestRecordedAt);
    }

    private StreamRecorder buildStreamRecorder(StreamerConfig streamerConfig,
                                               JSONObject downloadRecord,
                                               Date recordDate) {
        String downloadLink = resolveDownloadLink(
                streamerConfig, downloadRecord, downloadRecord.getString("id"));
        if (downloadLink == null) {
            return null;
        }

        eventPublisher.publishEvent(new StreamRecordEndEvent(this, streamerConfig.getName()));
        Map<String, String> extra = new HashMap<>();
        extra.put("streamTitle", downloadRecord.getString("streamtitle"));
        return new StreamUrlStreamRecorder(recordDate, streamerConfig.getRoomUrl(),
                getType().getType(), downloadLink, extra, true);
    }

    /**
     * 当前最高可用分辨率达到 1080 时立即下载；否则等待 30 分钟后下载最高可用分辨率。
     */
    private String resolveDownloadLink(StreamerConfig streamerConfig, JSONObject record, String videoId) {
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
