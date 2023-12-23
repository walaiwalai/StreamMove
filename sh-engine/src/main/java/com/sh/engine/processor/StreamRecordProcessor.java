package com.sh.engine.processor;

import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.manager.RecordManager;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.service.StreamRecordService;
import com.sh.engine.util.DateUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

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

    @Override
    public void processInternal(RecordContext context) {
        String name = context.getName();
        if (context.getLivingStreamer() != null && StringUtils.isNotBlank(context.getLivingStreamer().getRecordUrl())) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss");
            // 2.1 直播间开播
            RecordTask recordTask = RecordTask.builder()
                    .streamUrl(context.getLivingStreamer().getRecordUrl())
                    .recorderName(name)
                    .timeV(dateFormat.format(new Date()))
                    .build();
            processWhenRoomOnline(name, recordTask);
        } else {
            processWhenRoomOffline(name);
        }
    }

    private void processWhenRoomOnline(String name, RecordTask task) {
        boolean isLastRecording = statusManager.isOnRecord(name);
        boolean lastRoomOnline = statusManager.isRoomOnline(name);
        Recorder curRecorder = statusManager.getRecorderByStreamerName(name);

        // 一些状态位
        statusManager.onlineRoom(name);
        if (lastRoomOnline) {
            // 上次检测后认为房间在线
            if (isLastRecording) {
                if (!curRecorder.getRecorderStat()) {
                    // 房间在线但是直播流断开，重启
                    log.info("下载流{} 断开，但直播间在线，重启", curRecorder.getRecordTask().getDirName());
                    record(curRecorder);
                } else {
                    log.info("{} is recording...", curRecorder.getRecordTask().getRecorderName());
                }
            } else {
                // 之前认为在线，但不存在 Recorder，这种情况不应该出现
                record(Recorder.initRecorder(task));
            }
        } else {
            // 上次检测后认为房间不在线
            if (isLastRecording) {
                // 之前认为不在线，但存在 Recorder，这种情况不应该出现，可能未退出，应该将Record对应的线程杀死
                curRecorder.kill();
            } else {
                // 创建一个新的Recorder
                record(Recorder.initRecorder(task));
            }
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
            log.info("stop recording for {}", name);
            curRecorder.manualStopRecord();
            statusManager.deleteRoomPathStatus(curRecorder.getSavePath());
        }

        statusManager.deleteRecorder(name);
        statusManager.offlineRoom(name);
    }

    private void record(Recorder recorder) {
        String streamerName = recorder.getRecordTask().getRecorderName();
        // 1.创建录像文件夹
        File file = createVideoFile(recorder);
        String videoRecordPath = file.getAbsolutePath();
        recorder.setSavePath(videoRecordPath);

        recorder.writeInfoToFileStatus();

        // 2.注入状态（文件夹状态 和 录像机状态）
        statusManager.addRecorder(streamerName, recorder);
        statusManager.addRoomPathStatus(videoRecordPath);

        // 3，录像开始(长时间)
        streamRecordService.startRecord(recorder);

        // 4. 状态解除
        statusManager.deleteRoomPathStatus(videoRecordPath);
        statusManager.deleteRecorder(streamerName);
    }


    private File createVideoFile(Recorder recorder) {
        RecordTask recordTask = recorder.getRecordTask();
        String name = recorder.getRecordTask().getRecorderName();

        // 一级直播者目录
        File streamerFile = new File(ConfigFetcher.getInitConfig().getVideoSavePath(), name);
        if (!streamerFile.exists()) {
            streamerFile.mkdir();
        }

        // 二级时间目录
        File timeVFile = new File(streamerFile.getAbsolutePath(), recordTask.getTimeV());
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
