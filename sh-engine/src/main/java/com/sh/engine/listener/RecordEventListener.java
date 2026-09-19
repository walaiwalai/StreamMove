package com.sh.engine.listener;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.event.StreamRecordEndEvent;
import com.sh.engine.event.StreamRecordStartEvent;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.danmu.OrdinaryroadDamakuRecorder;
import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播事件监听器
 */
@Slf4j
@Component
public class RecordEventListener {
    private static final String DANMAKU_OSS_PREFIX = "danmaku";

    @Resource
    private OssUploadService ossUploadService;

    private final Map<String, DanmakuRecorder> danmakuRecorderMap = new ConcurrentHashMap<>();

    /**
     * 开始事件并启动弹幕录制。
     */
    @Async
    @EventListener
    public void handleDanmakuStart(StreamRecordStartEvent event) {
        String name = event.getStreamName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);

        // 检查是否需要录制弹幕
        if (BooleanUtils.isNotTrue(streamerConfig.isRecordDamaku())) {
            return;
        }

        if (danmakuRecorderMap.containsKey(name)) {
            return;
        }

        log.info("{} record start, begin recording danmaku", event.getStreamName());
        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder(streamerConfig, event.getRecordAt());
        recorder.init();
        recorder.start();

        danmakuRecorderMap.put(name, recorder);
    }

    /**
     * 结束事件中关闭弹幕录制，并将完整文件上传到 OSS。
     */
    @Async
    @EventListener
    public void handleDanmakuEnd(StreamRecordEndEvent event) {
        String streamerName = event.getStreamName();
        DanmakuRecorder recorder = danmakuRecorderMap.remove(streamerName);
        if (recorder == null) {
            return;
        }

        log.info("{} record end, stop recording danmaku", streamerName);
        recorder.close();

        File danmakuFile = recorder.getSaveFile();
        if (danmakuFile == null || !danmakuFile.isFile()) {
            log.warn("{} danmaku file does not exist, skip OSS upload, path: {}",
                    streamerName, danmakuFile == null ? null : danmakuFile.getAbsolutePath());
            return;
        }

        String recordTime = danmakuFile.getParentFile().getName();
        String objectKey = String.format("%s/%s/%s/%s", DANMAKU_OSS_PREFIX,
                streamerName, recordTime, danmakuFile.getName());
        try {
            ossUploadService.uploadAndGetUrl(danmakuFile, objectKey);
            log.info("{} danmaku file uploaded to OSS success, file: {}, key: {}", streamerName, danmakuFile.getAbsolutePath(), objectKey);
        } catch (Exception e) {
            log.error("{} danmaku file uploaded to OSS failed, file: {}, key: {}", streamerName, danmakuFile.getAbsolutePath(), objectKey);
        }
    }
}
