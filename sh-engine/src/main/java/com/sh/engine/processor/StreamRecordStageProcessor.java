package com.sh.engine.processor;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
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
        try {
            recorder.doRecord();
        } catch (Exception e) {
            log.error("record error, savePath: {}", recorder.getSavePath());
        }

        // 4.后置操作
        recordPostProcess(context, name);
    }


    private void recordPreProcess(RecordContext context, StreamerConfig streamerConfig) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(context.getRecorder().getRegDate());

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

        // 4.状态位注入
        statusManager.addRoomPathStatus(recordPath);
    }

    private void recordPostProcess(RecordContext context, String name) {
        // 1.状态位解除
        statusManager.deleteRoomPathStatus();

        // 2.修改streamer.json
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