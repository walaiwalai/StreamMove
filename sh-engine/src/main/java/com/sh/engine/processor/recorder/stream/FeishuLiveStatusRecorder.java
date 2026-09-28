package com.sh.engine.processor.recorder.stream;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import com.sh.engine.constant.RecordConstant;
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

    private void updateSession(RoomObservation observation) {
        String cacheKey = CACHE_KEY_PREFIX + DigestUtils.sha256Hex(baseToken + "|" + observation.getRoomUrl());
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
            } else if (session.getOfflineCheckCount() > 0) {
                session.setOfflineCheckCount(0);
                session.setFirstOfflineAtMillis(null);
                cacheManager.set(cacheKey, session);
                log.info("Feishu streamer is live again, canceled offline confirmation, name: {}", observation.getStreamerName());
            }
            return;
        }

        if (session == null) {
            return;
        }
        if (session.getOfflineCheckCount() == 0 || session.getFirstOfflineAtMillis() == null) {
            session.setFirstOfflineAtMillis(observation.getCheckedAt().getTime());
            session.setOfflineCheckCount(0);
        }
        session.setOfflineCheckCount(session.getOfflineCheckCount() + 1);
        log.info("Feishu streamer offline confirmation, name: {}, check: {}/{}",
                observation.getStreamerName(), session.getOfflineCheckCount(), RecordConstant.RECORD_RETRY_CNT);
        if (session.getOfflineCheckCount() < RecordConstant.RECORD_RETRY_CNT) {
            cacheManager.set(cacheKey, session);
            return;
        }

        session.setEndTimeMillis(session.getFirstOfflineAtMillis());
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
        private int offlineCheckCount;
        private Long firstOfflineAtMillis;

        public LiveSession() {
        }

        public LiveSession(String streamerName, long startTimeMillis, Long endTimeMillis) {
            this.streamerName = streamerName;
            this.startTimeMillis = startTimeMillis;
            this.endTimeMillis = endTimeMillis;
        }
    }
}
