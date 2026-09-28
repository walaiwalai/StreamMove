package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.manager.CacheManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.stream.FeishuLiveStatusRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.message.service.feishu.FeishuBitableClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

/** Checks every streamer listed in the Bitable base linked by room_url. */
@Component
@Slf4j
public class FeishuRoomChecker extends AbstractRoomChecker {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final FeishuBitableClient bitableClient;
    private final CacheManager cacheManager;

    @Value("${live.api.server.host}")
    private String liveHost;
    @Value("${live.api.server.port}")
    private String livePort;

    public FeishuRoomChecker(FeishuBitableClient bitableClient, CacheManager cacheManager) {
        this.bitableClient = bitableClient;
        this.cacheManager = cacheManager;
    }

    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (!bitableClient.isConfigured()) {
            log.warn("Feishu Bitable monitor check skipped, config: {}, credentials not configured",
                    streamerConfig.getName());
            return null;
        }
        String baseToken = extractBaseToken(streamerConfig.getRoomUrl());
        List<FeishuLiveStatusRecorder.RoomObservation> observations = new ArrayList<>();
        List<FeishuBitableClient.StreamerRoom> rooms = bitableClient.listStreamerRooms(baseToken);
        log.info("Feishu Bitable monitor check, config: {}, streamers: {}",
                streamerConfig.getName(), rooms.size());
        for (FeishuBitableClient.StreamerRoom room : rooms) {
            try {
                boolean live = isLive(room.getRoomUrl());
                log.info("Feishu streamer status checked, name: {}, live: {}", room.getName(), live);
                observations.add(new FeishuLiveStatusRecorder.RoomObservation(
                        room.getName(), room.getRoomUrl(), live, new Date()));
            } catch (Exception e) {
                log.error("Feishu streamer status check failed, name: {}, roomUrl: {}", room.getName(), room.getRoomUrl(), e);
            }
        }
        return new FeishuLiveStatusRecorder(new Date(), streamerConfig.getRoomUrl(), baseToken,
                observations, cacheManager, bitableClient);
    }

    private boolean isLive(String roomUrl) {
        JSONObject body = new JSONObject();
        body.put("url", roomUrl);
        body.put("quality", "原画");
        Request request = new Request.Builder()
                .url("http://" + liveHost + ":" + livePort + "/stream_info")
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString()))
                .build();
        String response = OkHttpClientUtil.execute(request);
        if (StringUtils.isBlank(response)) {
            throw new IllegalStateException("Live API returned an empty stream_info response");
        }
        JSONObject streamInfo = JSON.parseObject(response);
        if (streamInfo == null || streamInfo.getBoolean("is_live") == null) {
            throw new IllegalStateException("Live API stream_info response has no is_live field");
        }
        return streamInfo.getBooleanValue("is_live");
    }

    private String extractBaseToken(String baseUrl) {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(baseUrl);
        if (url == null) {
            throw new IllegalArgumentException("Feishu Bitable room_url is not a valid URL");
        }
        List<String> segments = url.pathSegments();
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (StringUtils.isNotBlank(segments.get(i + 1))) {
                if ("base".equals(segments.get(i))) {
                    return segments.get(i + 1);
                }
                if ("wiki".equals(segments.get(i))) {
                    return bitableClient.resolveWikiBaseToken(segments.get(i + 1));
                }
            }
        }
        throw new IllegalArgumentException("Feishu Bitable room_url has no base token");
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.FEISHU_BITABLE_MONITOR;
    }
}
