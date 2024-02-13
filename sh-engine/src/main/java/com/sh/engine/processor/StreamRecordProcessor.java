package com.sh.engine.processor;

import com.alibaba.fastjson.JSON;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.service.MsgSendService;
import com.sh.engine.service.StreamRecordService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 33
 **/
@Component
@Slf4j
public class StreamRecordProcessor extends AbstractRecordTaskProcessor {
    @Autowired
    private StatusManager statusManager;
    @Autowired
    private StreamRecordService streamRecordService;
    @Autowired
    private MsgSendService msgSendService;

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        if (context.getLivingStreamer() == null) {
            processWhenRoomOffline(name);
            return;
        }

        boolean isLastRecording = statusManager.isRoomPathFetchStream();
        if (isLastRecording) {
            log.info("{} is recording...", name);
            return;
        }

        if (statusManager.countOnRecord() >= ConfigFetcher.getInitConfig().getMaxRecordingCount()) {
            log.info("hit max recoding count, will return, name: {}.", name);
            return;
        }

        // 1. 前期准备
        recordPreProcess(context, streamerConfig);

        // 2.录像
        String videoRecordPath = statusManager.getCurRecordPath();
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            // 2.1 直播间开播
            Recorder recorder = Recorder.builder()
                    .streamUrl(context.getLivingStreamer().getStreamUrl())
                    .savePath(videoRecordPath)
                    .build();
            recordLiving(recorder);
        } else {
            // 2.2 是否有新的视频上传
            Recorder recorder = Recorder.builder()
                    .tsViews(context.getLivingStreamer().getTsViews())
                    .savePath(videoRecordPath)
                    .build();
            recordNewVideo(recorder);
        }

        // 3.后置操作
        recordPostProcess(context, streamerConfig);
    }


    private void recordPreProcess(RecordContext context, StreamerConfig streamerConfig) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ? dateFormat.format(new Date()) :
                dateFormat.format(context.getLivingStreamer().getTsViews().get(0).getRegDate());

        // 1. 创建录像文件
        String recordPath = createVideoFile(timeV, streamerConfig).getAbsolutePath();

        // 2.写fileStatus.json
        FileStatusModel fileStatusModel = new FileStatusModel();
        fileStatusModel.setPath(recordPath);
        fileStatusModel.setRecorderName(streamerConfig.getName());
        fileStatusModel.setIsPost(false);
        fileStatusModel.setIsFailed(false);
        fileStatusModel.setTimeV(timeV);
        fileStatusModel.writeSelfToFile(recordPath);

        // 3.将录像文件加到threadLocal
        StreamerInfoHolder.addRecordPath(recordPath);

        // 4.状态位注入
        statusManager.addRoomPathStatus(recordPath);
    }

    private void recordPostProcess(RecordContext context, StreamerConfig streamerConfig) {
        // 1.状态位解除
        statusManager.deleteRoomPathStatus();

        // 2.修改streamer.json
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String lastRecordTime = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ? dateFormat.format(new Date()) :
                dateFormat.format(context.getLivingStreamer().getTsViews().get(0).getRegDate());
        ConfigFetcher.refreshStreamer(StreamerConfig.builder()
                .name(streamerConfig.getName())
                .lastRecordTime(lastRecordTime)
                .build());
    }

    /**
     * 处理直播间下线的逻辑
     *
     * @param name
     */
    private void processWhenRoomOffline(String name) {
        boolean isLastOnRecord = statusManager.isRoomPathFetchStream();
        if (isLastOnRecord) {
            // 房间不在线，但仍在录制，先停止录制
            msgSendService.send("主播" + name + "下线了，停止录制..");
            log.info("stop recording for {}", name);
            statusManager.deleteRoomPathStatus();
        }
    }

    private void recordLiving(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        msgSendService.send("主播" + streamerName + "开播了，即将开始录制..");

        // 3，录像开始(长时间)
        streamRecordService.startRecord(recorder);
    }

    private void recordNewVideo(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        msgSendService.send("主播" + streamerName + "有新的视频上传，即将开始录制..");

        // 3，录像开始(长时间)
        streamRecordService.startDownload(recorder);
    }


    private File createVideoFile(String timeV, StreamerConfig streamerConfig) {
        String name = StreamerInfoHolder.getCurStreamerName();

        // 一级直播者目录
        File streamerFile = new File(streamerConfig.fetchSavePath(), name);
        if (!streamerFile.exists()) {
            streamerFile.mkdir();
        }

        // 二级时间目录
        File timeVFile = new File(streamerFile.getAbsolutePath(), timeV);
        if (!timeVFile.exists()) {
            timeVFile.mkdir();
        }
        return timeVFile;
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
