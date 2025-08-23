package com.sh.engine.processor.recorder;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.command.FfmpegRecordCmd;
import com.sh.engine.command.build.RecordCmdBuilder;
import com.sh.engine.command.callback.Recorder2StorageCallback;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * @Author : caiwen
 * @Date: 2025/1/28
 */
@Slf4j
public class StreamUrlRecorder extends Recorder {
    private final Recorder2StorageCallback recorder2StorageCallback = SpringUtil.getBean(Recorder2StorageCallback.class);
    private String streamUrl;

    public StreamUrlRecorder(Date regDate, Integer streamChannelType, String streamUrl) {
        super(regDate, streamChannelType, Maps.newHashMap());
        this.streamUrl = streamUrl;
    }

    @Override
    public void doRecord(String savePath) {
        recordOnline(savePath);
    }

    private void recordOnline(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);

        for (int i = 0; i < 3; i++) {
            log.info("living stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, 3);

            String cmd = builder.streamUrl(this.streamUrl).build();
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
            if (EnvUtil.isRecorderMode()) {
                // 增加上传视频的回调参数
                rfCmd.addSegmentCompletedCallback(recorder2StorageCallback);
            }

            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);
            if (rfCmd.isExitNormal()) {
                log.info("living stream record end, savePath: {}", savePath);
                break;
            } else {
                log.error("living stream record fail, savePath: {}", savePath);
            }
        }
    }
}
