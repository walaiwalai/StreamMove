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
import com.sh.engine.processor.recorder.stream.RangeVodStreamRecorder;
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
import java.util.concurrent.TimeUnit;

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

    /**
     * 同一场直播允许录像片段之间存在的最大间隔。
     */
    private static final long RECORD_GROUP_MAX_GAP_MILLIS = TimeUnit.MINUTES.toMillis(30);

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

        return new RangeVodStreamRecorder(recordedAt, streamerConfig.getRoomUrl(),
                getType().getType(), downloadLink, extra);
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
        RecordSnapshot snapshot = parseRecordSnapshot(dataArr);
        Set<String> originalCachedVideoIds = new HashSet<>(cacheBizManager.getStreamrecorderRunningIds(name));
        List<JSONObject> cachedRecords = loadCachedRecords(name, originalCachedVideoIds, snapshot.recordsById);
        cachedRecords = selectLatestPendingRecordGroup(streamerConfig, cachedRecords);
        Set<String> cachedVideoIds = getRecordIds(cachedRecords);

        if (CollectionUtils.isNotEmpty(snapshot.runningRecords)) {
            cacheRunningRecordGroup(streamerConfig, snapshot, cachedVideoIds);
            return null;
        }

        syncCachedRecordGroup(name, originalCachedVideoIds, cachedVideoIds);
        log.info("Streamrecorder check, lastRecordTime: {}, runningIds: {}, cachedIds: {}",
                streamerConfig.getLastRecordTime(), snapshot.runningVideoIds, cachedVideoIds);
        if (CollectionUtils.isNotEmpty(cachedRecords)) {
            return buildCachedRecordGroup(streamerConfig, cachedRecords);
        }
        return buildLatestFinishedRecord(streamerConfig, snapshot.finishedRecords);
    }

    /**
     * 解析 Streamrecorder.io 返回的有效录像，并按状态建立索引。
     */
    private RecordSnapshot parseRecordSnapshot(JSONArray records) {
        RecordSnapshot snapshot = new RecordSnapshot();
        for (int i = 0; i < records.size(); i++) {
            JSONObject record = records.getJSONObject(i);
            String videoId = record.getString("id");
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (StringUtils.isBlank(videoId) || recordedAt == null) {
                continue;
            }

            snapshot.recordsById.put(videoId, record);
            String status = record.getString("status");
            if (StringUtils.equals("running", status)) {
                snapshot.runningRecords.add(record);
                snapshot.runningVideoIds.add(videoId);
            } else if (StringUtils.equals("finished", status)) {
                snapshot.finishedRecords.add(record);
            }
        }
        return snapshot;
    }

    /**
     * 读取缓存对应的录像；缓存中任一录像已不在接口窗口时放弃整个旧分组。
     */
    private List<JSONObject> loadCachedRecords(String streamerName,
                                               Set<String> cachedVideoIds,
                                               Map<String, JSONObject> recordsById) {
        List<JSONObject> cachedRecords = new ArrayList<>();
        for (String videoId : cachedVideoIds) {
            JSONObject record = recordsById.get(videoId);
            if (record == null) {
                log.warn("Streamrecorder cached video is missing, clearing cache: {}, id: {}",
                        streamerName, videoId);
                cacheBizManager.clearStreamrecorderRunningIds(streamerName);
                return Collections.emptyList();
            }
            cachedRecords.add(record);
        }
        return cachedRecords;
    }

    /**
     * 合并当前运行中的录像，并只缓存时间上连续的最新一场直播。
     */
    private void cacheRunningRecordGroup(StreamerConfig streamerConfig,
                                         RecordSnapshot snapshot,
                                         Set<String> cachedVideoIds) {
        Date latestRunningAt = null;
        for (JSONObject record : snapshot.runningRecords) {
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (checkVodIsNew(streamerConfig, recordedAt)) {
                cachedVideoIds.add(record.getString("id"));
            }
            if (latestRunningAt == null || recordedAt.after(latestRunningAt)) {
                latestRunningAt = recordedAt;
            }
        }

        List<JSONObject> activeRecords = new ArrayList<>();
        for (String videoId : cachedVideoIds) {
            JSONObject record = snapshot.recordsById.get(videoId);
            if (record != null) {
                activeRecords.add(record);
            }
        }
        Set<String> activeVideoIds = getRecordIds(
                selectLatestPendingRecordGroup(streamerConfig, activeRecords));
        String streamerName = streamerConfig.getName();
        cacheBizManager.saveStreamrecorderRunningIds(streamerName, activeVideoIds);
        log.info("Streamrecorder check, lastRecordTime: {}, runningIds: {}, cachedIds: {}",
                streamerConfig.getLastRecordTime(), snapshot.runningVideoIds, activeVideoIds);
        eventPublisher.publishEvent(new StreamRecordStartEvent(this, streamerName, latestRunningAt));
    }

    /**
     * 将持久化缓存同步为归一化后的最新录像分组。
     */
    private void syncCachedRecordGroup(String streamerName,
                                       Set<String> originalVideoIds,
                                       Set<String> retainedVideoIds) {
        if (originalVideoIds.equals(retainedVideoIds)) {
            return;
        }
        Set<String> discardedVideoIds = new HashSet<>(originalVideoIds);
        discardedVideoIds.removeAll(retainedVideoIds);
        log.warn("Streamrecorder discarded stale cached records: {}, discardedIds: {}, retainedIds: {}",
                streamerName, discardedVideoIds, retainedVideoIds);
        cacheBizManager.saveStreamrecorderRunningIds(streamerName, retainedVideoIds);
    }

    /**
     * 缓存分组全部结束后下载其中时长最长的录像，并用最新开始时间推进游标。
     */
    private StreamRecorder buildCachedRecordGroup(StreamerConfig streamerConfig,
                                                   List<JSONObject> cachedRecords) {
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

    /**
     * 无运行缓存时只下载最新的已结束录像，不补录更早记录。
     */
    private StreamRecorder buildLatestFinishedRecord(StreamerConfig streamerConfig,
                                                     List<JSONObject> finishedRecords) {
        JSONObject latestRecord = null;
        Date latestRecordedAt = null;
        for (JSONObject record : finishedRecords) {
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (checkVodIsNew(streamerConfig, recordedAt)
                    && (latestRecordedAt == null || recordedAt.after(latestRecordedAt))) {
                latestRecord = record;
                latestRecordedAt = recordedAt;
            }
        }
        return latestRecord == null
                ? null
                : buildStreamRecorder(streamerConfig, latestRecord, latestRecordedAt);
    }

    /**
     * 只保留尚未处理且时间上连续的最新一场直播录像，避免失败缓存跨场次累积。
     */
    private List<JSONObject> selectLatestPendingRecordGroup(StreamerConfig streamerConfig,
                                                            List<JSONObject> records) {
        List<JSONObject> sortedRecords = new ArrayList<>();
        for (JSONObject record : records) {
            Date recordedAt = parseGMT8Date(record.getString("recorded_at"));
            if (recordedAt != null && checkVodIsNew(streamerConfig, recordedAt)) {
                sortedRecords.add(record);
            }
        }
        sortedRecords.sort(Comparator.comparing(
                record -> parseGMT8Date(record.getString("recorded_at"))));

        List<JSONObject> latestGroup = new ArrayList<>();
        long latestGroupEnd = Long.MIN_VALUE;
        for (JSONObject record : sortedRecords) {
            long recordStart = parseGMT8Date(record.getString("recorded_at")).getTime();
            long durationSeconds = Math.max(0L, record.getLongValue("duration"));
            long recordEnd = recordStart + TimeUnit.SECONDS.toMillis(durationSeconds);
            if (!latestGroup.isEmpty()
                    && recordStart - latestGroupEnd > RECORD_GROUP_MAX_GAP_MILLIS) {
                latestGroup.clear();
                latestGroupEnd = Long.MIN_VALUE;
            }
            latestGroup.add(record);
            latestGroupEnd = Math.max(latestGroupEnd, recordEnd);
        }
        return latestGroup;
    }

    /**
     * 提取录像列表中的视频 ID，供缓存与当前有效分组保持一致。
     */
    private Set<String> getRecordIds(List<JSONObject> records) {
        Set<String> videoIds = new LinkedHashSet<>();
        for (JSONObject record : records) {
            videoIds.add(record.getString("id"));
        }
        return videoIds;
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
        return new RangeVodStreamRecorder(recordDate, streamerConfig.getRoomUrl(),
                getType().getType(), downloadLink, extra);
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

    /**
     * 单次接口响应中已校验的录像索引和状态集合。
     */
    private static final class RecordSnapshot {
        private final Map<String, JSONObject> recordsById = new HashMap<>();
        private final List<JSONObject> runningRecords = new ArrayList<>();
        private final List<JSONObject> finishedRecords = new ArrayList<>();
        private final Set<String> runningVideoIds = new LinkedHashSet<>();
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
