package com.sh.engine.processor;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.repo.StreamerRepoService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 33
 **/
@Component
@Slf4j
public class StreamRecordStageProcessor extends AbstractStageProcessor {
    @Value("${sh.video-save.path}")
    private String videoSavePath;

    @Autowired
    private StatusManager statusManager;
    @Autowired
    private MsgSendService msgSendService;
    @Autowired
    private StreamerRepoService streamerRepoService;
    @Autowired
    private ConfigFetcher configFetcher;

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        // 是否已经结束录制
        if (context.getRecorder() == null) {
            return;
        }
        String savePath = genRegPathByRegDate(context.getRecorder().getRegDate(), name);

        // 1. 前期准备
        recordPreProcess(streamerConfig, savePath);

        // 2. 录制
        statusManager.addRoomPathStatus(savePath);
        try {
            // 录像(长时间)
            context.getRecorder().doRecord(savePath);
        } catch (Exception e) {
            log.error("record error, savePath: {}", savePath, e);
            throw e;
        } finally {
            statusManager.deleteRoomPathStatus();
        }
        // 3. 后置操作
        recordPostProcess(context.getRecorder(), name);
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

        // 4. 发送消息
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String msg = BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline()) ?
                "主播" + streamerName + "开播了，即将开始录制.." + "存储位置：" + recordPath :
                "主播" + streamerName + "有新的视频上传，即将开始录制.." + "存储位置：" + recordPath;
        msgSendService.sendText(msg);
    }

    private void recordPostProcess(Recorder recorder, String name) {
        // 更新数据库
        streamerRepoService.updateLastRecordTime(name, recorder.getRegDate());

        // 重新刷一下内存
        configFetcher.refreshStreamer(name);
    }

    /**
     * 生成录像保存地址
     *
     * @param date
     * @return
     */
    private String genRegPathByRegDate( Date date, String name) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(date);

        File regFile = new File(new File(videoSavePath, name), timeV);
        return regFile.getAbsolutePath();
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
