package com.sh.engine.processor.recorder.stream;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.RecordCmdBuilder;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.StreamLinkCheckCmd;
import com.sh.engine.model.ffmpeg.StreamLinkUrlFetchCmd;
import com.sh.engine.model.ffmpeg.StreamMetaDetectCmd;
import com.sh.engine.model.video.StreamMetaInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * streamlink录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 12
 **/
@Slf4j
public class StreamLinkStreamRecorder extends StreamRecorder {
    private String qualityParam;

    public StreamLinkStreamRecorder(Date regDate, Integer streamChannelType, String roomUrl) {
        super(regDate, roomUrl, streamChannelType, Maps.newHashMap());
    }

    @Override
    public void start(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            // 录制在线视频
            recordOnline(savePath);
        } else {
            // 录制回放
            recordReplay(savePath);
        }
    }

    @Override
    public StreamMetaInfo fetchMeta() {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());

        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.roomUrl);
        checkCmd.execute(40);
        this.qualityParam = checkCmd.selectQuality(Optional.ofNullable(streamerConfig.getRecordQuality()).orElse(0));

        StreamLinkUrlFetchCmd streamLinkUrlFetchCmd = new StreamLinkUrlFetchCmd(this.roomUrl, this.qualityParam);
        streamLinkUrlFetchCmd.execute(20);

        StreamMetaDetectCmd streamMetaDetectCmd = new StreamMetaDetectCmd(streamLinkUrlFetchCmd.getStreamUrl());
        streamMetaDetectCmd.execute(60);

        return streamMetaDetectCmd.getMetaInfo();
    }

    private void recordReplay(String savePath) {
        if (!streamMeta.isValid() || streamMeta.getHeight() < 720) {
            log.error("Resolution is too low {}, stopping recording...", JSON.toJSONString(streamMeta));
            FileUtils.deleteQuietly(new File(savePath));
            throw new StreamerRecordException(ErrorEnum.RECORD_BAD_QUALITY);
        }

        log.info("Resolution is OK {}, start recording...", JSON.toJSONString(streamMeta));
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());

        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);
        String cmd = builder.streamlink(this.roomUrl, this.qualityParam).build();

        FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);

        // 长时间录播（阻塞）
        rfCmd.execute(24 * 3600L);
        if (!rfCmd.isNormalExit()) {
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
            StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.roomUrl);
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
            String cmd = builder.streamlink(this.roomUrl, this.qualityParam).build();

            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);

            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (!rfCmd.isNormalExit()) {
                log.error("living stream record fail, savePath: {}", savePath);
//                throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
            }
        }
        log.info("living stream record end, savePath: {}", savePath);
    }
}
