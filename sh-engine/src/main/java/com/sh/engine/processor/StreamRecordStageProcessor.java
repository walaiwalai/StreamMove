package com.sh.engine.processor;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 33
 **/
@Component
@Slf4j
public class StreamRecordStageProcessor extends AbstractStageProcessor {
    @Autowired
    private StatusManager statusManager;
    @Autowired
    private MsgSendService msgSendService;

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        if (context.getRecorder() == null) {
            return;
        }

        boolean isLastRecording = statusManager.isRoomPathFetchStream();
        if (isLastRecording) {
            log.info("{} is recording...", name);
            return;
        }

        if (!streamerConfig.isRecordWhenOnline() && statusManager.count() >= ConfigFetcher.getInitConfig().getMaxRecordingCount()) {
            // 录像拦截，直播不拦截
            log.info("hit max recoding count, will return, name: {}.", name);
            return;
        }

        String recordPath = context.getRecorder().getSavePath();
        statusManager.addRoomPathStatus(recordPath);

        try {
            // 1. 前期准备
            recordPreProcess(context, streamerConfig);

            // 2.发消息
            String streamerName = StreamerInfoHolder.getCurStreamerName();
            String msg = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ?
                    "主播" + streamerName + "开播了，即将开始录制.." :
                    "主播" + streamerName + "有新的视频上传，即将开始录制..";
            msgSendService.sendText(msg);

            // 3 录像(长时间)
            Recorder recorder = context.getRecorder();
            recorder.doRecord();

            // 4 检查以下视频切片是否合法
            if (!checkRecordSeg(context)) {
                FileUtils.deleteQuietly(new File(context.getRecorder().getSavePath()));
                throw new StreamerRecordException(ErrorEnum.RECORD_SEG_ERROR);
            }

            // 5.后置操作
            recordPostProcess(context, name);
        } catch (Exception e) {
            log.error("record error, savePath: {}", recordPath, e);
        } finally {
            statusManager.deleteRoomPathStatus();
        }
    }


    private boolean checkRecordSeg(RecordContext context) {
        // 抽样视频片段是否合法
        String recordPath = context.getRecorder().getSavePath();
        File segFile = new File(recordPath, VideoFileUtil.genSegName(1));
        if (!segFile.exists()) {
            return false;
        }

        String querySizeCmd = "ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 " + segFile.getAbsolutePath();
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(querySizeCmd);
        detectCmd.execute();
        int height = detectCmd.getHeight();

        return height >= 1080;
    }


    private void recordPreProcess(RecordContext context, StreamerConfig streamerConfig) {

        // 1. 创建录像文件
        String recordPath = context.getRecorder().getSavePath();
        File recordFile = new File(recordPath);
        if (!recordFile.exists()) {
            recordFile.mkdirs();
        }

        // 2.写fileStatus.json
        FileStatusModel fileStatusModel = new FileStatusModel();
        fileStatusModel.setPlatforms(streamerConfig.getUploadPlatforms());
        fileStatusModel.writeSelfToFile(recordPath);

        // 3.将录像文件加到threadLocal
        StreamerInfoHolder.addRecordPath(recordPath);
    }

    private void recordPostProcess(RecordContext context, String name) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String lastRecordTime = dateFormat.format(context.getRecorder().getRegDate());
        ConfigFetcher.refreshStreamer(name, StreamerConfig.builder()
                .lastRecordTime(lastRecordTime)
                .build());
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.ROOM_CHECK_FINISH;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.STREAM_RECORD;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.STREAM_RECORD_FINISH;
    }
}
