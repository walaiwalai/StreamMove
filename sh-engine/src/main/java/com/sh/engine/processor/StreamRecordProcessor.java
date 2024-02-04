package com.sh.engine.processor;

import com.alibaba.fastjson.JSON;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.model.record.TsRecordInfo;
import com.sh.engine.service.MsgSendService;
import com.sh.engine.service.StreamRecordService;
import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class StreamRecordProcessor extends AbstractRecordTaskProcessor {
    private static final Logger log = LoggerFactory.getLogger(StreamRecordProcessor.class);

    @Autowired
    private StatusManager statusManager;
    @Autowired
    private StreamRecordService streamRecordService;
    @Autowired
    private MsgSendService msgSendService;

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        if (context.getLivingStreamer() == null) {
            processWhenRoomOffline(name);
            return;
        }

        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ? dateFormat.format(new Date()) :
                dateFormat.format(context.getLivingStreamer().getTsRecordInfo().getRegDate());
        String videoRecordPath = createVideoFile(timeV).getAbsolutePath();
        StreamerInfoHolder.addRecordPath(videoRecordPath);

        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            // 2.1 直播间开播
            Recorder recorder = Recorder.builder()
                    .streamUrl(context.getLivingStreamer().getStreamUrl())
                    .timeV(timeV)
                    .savePath(videoRecordPath)
                    .build();
            processWhenRoomOnline(recorder);
        } else {
            // 2.2 是否有新的视频上传
            Recorder recorder = Recorder.builder()
                    .tsRecordInfo(context.getLivingStreamer().getTsRecordInfo())
                    .timeV(timeV)
                    .savePath(videoRecordPath)
                    .build();
            processWhenNewVideoUpload(recorder);
        }
    }

    private void processWhenRoomOnline(Recorder recorder) {
        String name = StreamerInfoHolder.getCurStreamerName();
        boolean isLastRecording = statusManager.isOnRecord(name);

        if (isLastRecording) {
            log.info("{} is recording...", name);
        } else {
            // 创建一个新的Recorder
            recordLiving(recorder);
        }
    }

    private void processWhenNewVideoUpload(Recorder recorder) {
        String name = StreamerInfoHolder.getCurStreamerName();
        boolean isLastRecording = statusManager.isOnRecord(name);

        if (isLastRecording) {
            log.info("{}'s new video is recording...", name);
        } else {
            // 之前认为在线，但不存在 Recorder，这种情况不应该出现
            recordNewVideo(recorder);
        }
    }

    /**
     * 处理直播间下线的逻辑
     *
     * @param name
     */
    private void processWhenRoomOffline(String name) {
        boolean isLastOnRecord = statusManager.isOnRecord(name);
        Recorder curRecorder = statusManager.getRecorderByStreamerName(name);
        if (isLastOnRecord) {
            // 房间不在线，但仍在录制，先停止录制
            msgSendService.send("主播" + name + "下线了，停止录制..");
            log.info("stop recording for {}", name);
            statusManager.deleteRoomPathStatus(curRecorder.getSavePath());
        }

        statusManager.deleteRecorder(name);
//        statusManager.offlineRoom(name);
    }

    private void recordLiving(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        if (statusManager.countOnRecord() >= ConfigFetcher.getInitConfig().getMaxRecordingCount()) {
            List<String> recodingNames = statusManager.listOnRecordName();
            log.info("hit max recoding count, will return, name: {}, recoding: {}", streamerName, JSON.toJSONString(recodingNames));
            return;
        }

        msgSendService.send("主播" + streamerName + "开播了，即将开始录制..");

        // 1.创建录像文件夹
        recorder.writeInfoToFileStatus();

        // 2.注入状态（文件夹状态 和 录像机状态）
        statusManager.addRecorder(streamerName, recorder);
        statusManager.addRoomPathStatus(recorder.getSavePath());

        // 3，录像开始(长时间)
        streamRecordService.startRecord(recorder);
        // 修改streamer.json
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        ConfigFetcher.refreshStreamer(StreamerConfig.builder()
                .name(streamerName)
                .lastRecordTime(dateFormat.format(new Date()))
                .build());

        // 4. 状态解除
        statusManager.deleteRoomPathStatus(recorder.getSavePath());
        statusManager.deleteRecorder(streamerName);
    }

    private void recordNewVideo(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        if (statusManager.countOnRecord() >= ConfigFetcher.getInitConfig().getMaxRecordingCount()) {
            List<String> recodingNames = statusManager.listOnRecordName();
            log.info("hit max recoding count, will return, name: {}, recoding: {}", streamerName, JSON.toJSONString(recodingNames));
            return;
        }

        msgSendService.send("主播" + streamerName + "有新的视频上传，即将开始录制..");
        TsRecordInfo tsRecordInfo = recorder.getTsRecordInfo();

        // 1.创建录像文件夹
        String videoRecordPath = recorder.getSavePath();
        recorder.writeInfoToFileStatus();

        // 2.注入状态（文件夹状态 和 录像机状态）
        statusManager.addRecorder(streamerName, recorder);
        statusManager.addRoomPathStatus(videoRecordPath);

        // 3，录像开始(长时间)
        streamRecordService.startDownload(recorder);
        // 修改streamer.json
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        ConfigFetcher.refreshStreamer(StreamerConfig.builder()
                .name(streamerName)
                .lastRecordTime(dateFormat.format(Optional.ofNullable(tsRecordInfo.getRegDate()).orElse(new Date())))
                .build());

        // 4. 状态解除
        statusManager.deleteRoomPathStatus(videoRecordPath);
        statusManager.deleteRecorder(streamerName);
    }


    private File createVideoFile(String timeV) {
        String name = StreamerInfoHolder.getCurStreamerName();

        // 一级直播者目录
        File streamerFile = new File(ConfigFetcher.getInitConfig().getVideoSavePath(), name);
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
