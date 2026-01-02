package com.sh.engine.processor.recorder.stream;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.model.RecordCmdBuilder;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.StreamMetaDetectCmd;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2025/1/28
 */
@Slf4j
public class StreamUrlStreamRecorder extends StreamRecorder {
    private final String streamUrl;

    public StreamUrlStreamRecorder(Date regDate, String roomUrl, Integer streamChannelType, String streamUrl) {
        super(regDate, roomUrl, streamChannelType, Maps.newHashMap());
        this.streamUrl = streamUrl;
    }

    public StreamUrlStreamRecorder(Date regDate, String roomUrl, Integer streamChannelType, String streamUrl, Map<String, String> extraInfo) {
        super(regDate, roomUrl, streamChannelType, extraInfo);
        this.streamUrl = streamUrl;
    }

    @Override
    public void start(String savePath) {
        recordOnline(savePath);
    }

    @Override
    protected void initParam(String savePath) {
    }

    private void recordOnline(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);

        for (int i = 0; i < 3; i++) {
            log.info("living stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, 3);

            String cmd = builder.streamUrl(this.streamUrl).build();
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);
            if (rfCmd.isNormalExit()) {
                log.info("living stream record end, savePath: {}", savePath);
                break;
            } else {
                log.error("living stream record fail, savePath: {}", savePath);
            }
        }
    }
}
