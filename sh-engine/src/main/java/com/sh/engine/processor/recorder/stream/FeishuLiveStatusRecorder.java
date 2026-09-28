package com.sh.engine.processor.recorder.stream;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.message.service.feishu.FeishuBitableClient;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Persists live state transitions; it never creates media files. */
@Slf4j
public class FeishuLiveStatusRecorder extends StreamRecorder {
    private static final String CACHE_KEY_PREFIX = "feishu:live:session:";
    private final String baseToken;
    private final List<RoomObservation> observations;
    private final CacheManager cacheManager;
    private final FeishuBitableClient bitableClient;

    public FeishuLiveStatusRecorder(Date checkedAt, String baseUrl, String baseToken,
                                    List<RoomObservation> observations, CacheManager cacheManager,
                                    FeishuBitableClient bitableClient) {
        super(checkedAt, baseUrl, StreamChannelTypeEnum.FEISHU_BITABLE_MONITOR.getType(), Collections.emptyMap());
        this.baseToken = baseToken;
        this.observations = observations;
        this.cacheManager = cacheManager;
        this.bitableClient = bitableClient;
    }

    @Override
    public void start(String ignoredSavePath) {
        for (RoomObservation observation : observations) {
            try {
                updateSession(observation);
            } catch (Exception e) {
                log.error("Feishu live session update failed, name: {}, roomUrl: {}",
                        observation.getStreamerName(), observation.getRoomUrl(), e);
            }
        }
    }

    public static boolean hasActiveSession(CacheManager cacheManager, String baseToken, String roomUrl) {
        LiveSession session = cacheManager.get(cacheKey(baseToken, roomUrl), new TypeReference<LiveSession>() {});
        return session != null && session.getEndTimeMillis() == null;
    }

    private static String cacheKey(String baseToken, String roomUrl) {
        return CACHE_KEY_PREFIX + DigestUtils.sha256Hex(baseToken + "|" + roomUrl);
    }

    private void updateSession(RoomObservation observation) {
        String cacheKey = cacheKey(baseToken, observation.getRoomUrl());
        LiveSession session = cacheManager.get(cacheKey, new TypeReference<LiveSession>() {});

        // A failed Bitable write is retried before a later broadcast can start a new session.
        if (session != null && session.getEndTimeMillis() != null) {
            bitableClient.createLiveRecord(baseToken, session.getStreamerName(), session.getStartTimeMillis(), session.getEndTimeMillis());
            cacheManager.delete(cacheKey);
            session = null;
        }

        if (observation.isLive()) {
            if (session == null) {
                cacheManager.set(cacheKey, new LiveSession(observation.getStreamerName(), observation.getCheckedAt().getTime(), null));
                log.info("Feishu streamer started live, name: {}, roomUrl: {}", observation.getStreamerName(), observation.getRoomUrl());
            }
            return;
        }
        if (session == null) {
            return;
        }
        session.setEndTimeMillis(observation.getCheckedAt().getTime());
        cacheManager.set(cacheKey, session);
        bitableClient.createLiveRecord(baseToken, session.getStreamerName(), session.getStartTimeMillis(), session.getEndTimeMillis());
        cacheManager.delete(cacheKey);
        log.info("Feishu streamer ended live, name: {}, roomUrl: {}", session.getStreamerName(), observation.getRoomUrl());
    }

    @Override
    protected void initParam(String ignoredSavePath) {
        // No media is recorded for this monitor.
    }

    @Getter
    public static class RoomObservation {
        private final String streamerName;
        private final String roomUrl;
        private final boolean live;
        private final Date checkedAt;

        public RoomObservation(String streamerName, String roomUrl, boolean live, Date checkedAt) {
            this.streamerName = streamerName;
            this.roomUrl = roomUrl;
            this.live = live;
            this.checkedAt = checkedAt;
        }
    }

    @Data
    public static class LiveSession {
        private String streamerName;
        private long startTimeMillis;
        private Long endTimeMillis;

        public LiveSession() {
        }

        public LiveSession(String streamerName, long startTimeMillis, Long endTimeMillis) {
            this.streamerName = streamerName;
            this.startTimeMillis = startTimeMillis;
            this.endTimeMillis = endTimeMillis;
        }
    }
}
