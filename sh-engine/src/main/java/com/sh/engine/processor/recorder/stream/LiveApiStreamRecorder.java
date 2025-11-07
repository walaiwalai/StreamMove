package com.sh.engine.processor.recorder.stream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.RecordCmdBuilder;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.StreamMetaDetectCmd;
import com.sh.engine.model.video.StreamMetaInfo;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2025/10/26
 */
@Slf4j
public class LiveApiStreamRecorder extends StreamRecorder {
    private static final String liveHost = EnvUtil.getEnvValue("live.api.server.host");
    private static final String livePort = EnvUtil.getEnvValue("live.api.server.port");

    private String streamUrl;
    private final String quality;

    public LiveApiStreamRecorder(Date regDate, String roomUrl, Integer streamChannelType, Map<String, String> extraInfo, String streamUrl, String quality) {
        super(regDate, roomUrl, streamChannelType, extraInfo);
        this.streamUrl = streamUrl;
        this.quality = quality;
    }

    @Override
    public void start( String savePath ) {
        recordOnline(savePath);
    }

    @Override
    public StreamMetaInfo fetchMeta(String savePath) {
        this.streamUrl = getLiveStreamUrl();

        StreamMetaDetectCmd streamMetaDetectCmd = new StreamMetaDetectCmd(this.streamUrl);
        streamMetaDetectCmd.execute(60);

        return streamMetaDetectCmd.getMetaInfo();
    }

    private void recordOnline(String savePath) {
        int totalCnt = RecordConstant.RECORD_RETRY_CNT;
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);

        for (int i = 0; i < totalCnt; i++) {
            // 如果是在线的录制，再次检查是否在线
            String liveStreamUrl = i == 0 ? this.streamUrl : getLiveStreamUrl();
            if (StringUtils.isBlank(liveStreamUrl)) {
                try {
                    // 睡40s防止重试太快
                    Thread.sleep(40 * 1000);
                } catch (InterruptedException e) {
                }
                log.info("live api stream offline confirm, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
                continue;
            }

            log.info("live api stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
            String cmd = builder.streamUrl(liveStreamUrl).build();
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);

            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (!rfCmd.isNormalExit()) {
                log.error("live api stream record fail, savePath: {}", savePath);
            }
        }
        log.info("live api stream record end, savePath: {}", savePath);
    }

    private String getLiveStreamUrl() {
        MediaType mediaType = MediaType.parse("application/json");
        Map<String, String> params = Maps.newHashMap();
        params.put("url", this.roomUrl);
        params.put("quality", quality);
        RequestBody body = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url("http://" + liveHost + ":" + livePort + "/stream_info")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        if (StringUtils.isBlank(resp)) {
            log.error("no resp for stream info");
            return null;
        }
        JSONObject respObj = JSON.parseObject(resp);
        if (BooleanUtils.isNotTrue(respObj.getBoolean("is_live"))) {
            return null;
        }
        return respObj.getString("record_url");
    }
}
