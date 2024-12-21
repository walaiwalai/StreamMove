package com.sh.engine.processor;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
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

        // 是否正在录制
        boolean isLastRecording = statusManager.isRoomPathFetchStream();
        if (isLastRecording) {
            log.info("{} is recording...", name);
            return;
        }

        // 录播最大个数限制
        if (!streamerConfig.isRecordWhenOnline() && statusManager.count() >= ConfigFetcher.getInitConfig().getMaxRecordingCount()) {
            // 录像拦截，直播不拦截
            log.info("hit max recoding count, will return, name: {}.", name);
            return;
        }

        // 是否已经结束录制
        if (context.getRecorder() == null) {
            return;
        }

        String savePath = VideoFileUtil.genRegPathByRegDate(context.getRecorder().getRegDate(), name);

        // 1. 前期准备
        recordPreProcess(streamerConfig, savePath);

        // 2. 录制
        doRecord(context.getRecorder(), savePath);

        // 3. 后置操作
        recordPostProcess(context.getRecorder(), name);
    }

    private void doRecord(Recorder recorder, String savePath) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);

        // 发消息
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String msg = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ?
                "主播" + streamerName + "开播了，即将开始录制.." + "存储位置：" + savePath :
                "主播" + streamerName + "有新的视频上传，即将开始录制.." + "存储位置：" + savePath;
        msgSendService.sendText(msg);

        statusManager.addRoomPathStatus(savePath);
        try {
            // 录像(长时间)
            recorder.doRecord(savePath);
        } catch (Exception e) {
            log.error("record error, savePath: {}", savePath, e);
        } finally {
            statusManager.deleteRoomPathStatus();
        }
    }

    private void recordPreProcess(StreamerConfig streamerConfig, String recordPath) {
        // 1. 创建录像文件
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

    private void recordPostProcess(Recorder recorder, String name) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String lastRecordTime = dateFormat.format(recorder.getRegDate());
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
