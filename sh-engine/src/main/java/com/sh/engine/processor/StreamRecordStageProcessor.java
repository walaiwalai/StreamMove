package com.sh.engine.processor;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;

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
    @Autowired
    private CacheManager cacheManager;

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
        String punishKey = getRecordPunishKey();
        String closingEndKey = getClosingEndKey();
        String recordEndPath = cacheManager.get(closingEndKey);
        if (context.getRecorder() == null && StringUtils.isBlank(recordEndPath)) {
            return;
        }

        if (StringUtils.isNotBlank(cacheManager.get(punishKey))) {
            throw new StreamerRecordException(ErrorEnum.RECORD_PUNISH_FOR_BAD_QUALITY);
        }

        if (context.getRecorder() != null) {
            String savePath = StringUtils.isNotBlank(recordEndPath) ?
                    recordEndPath :
                    VideoFileUtil.genRegPathByRegDate(context.getRecorder().getRegDate(), name);

            // 录制
            doRecord(context.getRecorder(), savePath);

            // 结束的录制地址
            cacheManager.set(closingEndKey, savePath, 15, TimeUnit.MINUTES);

            // 检查以下视频切片是否合法
            if (!checkRecordSeg(savePath)) {
                FileUtils.deleteQuietly(new File(savePath));
                cacheManager.set(punishKey, "true", 30, TimeUnit.MINUTES);
                throw new StreamerRecordException(ErrorEnum.RECORD_DELETE_FOR_BAD_QUALITY);
            }

            // 后置操作
            recordPostProcess(context.getRecorder(), name);
        }

        // 是否录播刚结束
        if (StringUtils.isNotBlank(cacheManager.get(closingEndKey))) {
            throw new StreamerRecordException(ErrorEnum.RECORD_SEG_ERROR);
        }
    }

    private String getRecordPunishKey() {
        return "punishRecord_" + StreamerInfoHolder.getCurStreamerName();
    }

    private String getClosingEndKey() {
        return "recordEnd_" + StreamerInfoHolder.getCurStreamerName();
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

        // 真正录制视频
        statusManager.addRoomPathStatus(savePath);
        try {
            // 1. 前期准备
            recordPreProcess(streamerConfig, savePath);

            // 2 录像(长时间)
            recorder.doRecord(savePath);
        } catch (Exception e) {
            log.error("record error, savePath: {}", savePath, e);
        } finally {
            statusManager.deleteRoomPathStatus();
        }
    }


    private boolean checkRecordSeg(String recordPath) {
        // 抽样视频片段是否合法
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
