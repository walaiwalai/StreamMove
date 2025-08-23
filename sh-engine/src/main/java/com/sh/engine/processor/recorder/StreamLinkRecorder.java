package com.sh.engine.processor.recorder;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.command.FfmpegRecordCmd;
import com.sh.engine.command.StreamLinkCheckCmd;
import com.sh.engine.command.build.RecordCmdBuilder;
import com.sh.engine.command.callback.Recorder2StorageCallback;
import com.sh.engine.constant.RecordConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.Optional;

/**
 * streamlink录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 12
 **/
@Slf4j
public class StreamLinkRecorder extends Recorder {
    private final Recorder2StorageCallback recorder2StorageCallback = SpringUtil.getBean(Recorder2StorageCallback.class);
    private String streamUrl;

    public StreamLinkRecorder(Date regDate, Integer streamChannelType, String streamUrl) {
        super(regDate, streamChannelType, Maps.newHashMap());
        this.streamUrl = streamUrl;
    }

    @Override
    public void doRecord(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            // 录制在线视频
            recordOnline(savePath);
        } else {
            // 录制回放
            recordReplay(savePath);
        }
    }

    private void recordReplay(String savePath) {
        // 如果是在线的录制，再次检查是否在线
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.streamUrl);
        checkCmd.execute(40);
        String bestResolution = checkCmd.getBestResolution();
        if (!StringUtils.contains(bestResolution, "720") && !StringUtils.contains(bestResolution, "1080")) {
            log.error("Resolution is too low {}, stopping recording...", bestResolution);
            FileUtils.deleteQuietly(new File(savePath));
            throw new StreamerRecordException(ErrorEnum.RECORD_BAD_QUALITY);
        }

        log.info("Resolution is OK {}, start recording...", bestResolution);
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        String qualityParam = checkCmd.selectQuality(Optional.ofNullable(streamerConfig.getRecordQuality()).orElse(0));
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);
        String cmd = builder.streamlink(this.streamUrl, qualityParam).build();

        FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
        if (EnvUtil.isRecorderMode()) {
            // 增加上传视频的回调参数
            rfCmd.addSegmentCompletedCallback(recorder2StorageCallback);
        }

        // 长时间录播（阻塞）
        rfCmd.execute(24 * 3600L);
        if (!rfCmd.isExitNormal()) {
            log.error("replay stream record fail, savePath: {}", savePath);
            throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
        }

        log.info("replay stream record end, savePath: {}", savePath);
    }


    private void recordOnline(String savePath) {
        int totalCnt = RecordConstant.RECORD_RETRY_CNT;
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);
        for (int i = 0; i < totalCnt; i++) {
            // 如果是在线的录制，再次检查是否在线
            StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.streamUrl);
            checkCmd.execute(40);
            if (!checkCmd.isStreamOnline()) {
                try {
                    // 睡40s防止重试太快
                    Thread.sleep(40 * 1000);
                } catch (InterruptedException e) {
                }
                log.info("living stream offline confirm, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
                continue;
            }

            log.info("living stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
            String qualityParam = checkCmd.selectQuality(Optional.ofNullable(streamerConfig.getRecordQuality()).orElse(0));
            String cmd = builder.streamlink(this.streamUrl, qualityParam).build();

            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
            if (EnvUtil.isRecorderMode()) {
                // 增加上传视频的回调参数
                rfCmd.addSegmentCompletedCallback(recorder2StorageCallback);
            }
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (!rfCmd.isExitNormal()) {
                log.error("living stream record fail, savePath: {}", savePath);
//                throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
            }
        }
        log.info("living stream record end, savePath: {}", savePath);
    }
}
