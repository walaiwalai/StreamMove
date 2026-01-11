package com.sh.engine.listener;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.event.StreamRecordEndEvent;
import com.sh.engine.event.StreamRecordStartEvent;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.danmu.OrdinaryroadDamakuRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import static com.sh.engine.constant.RecordConstant.DAMAKU_TXT_ALL_FILE;

/**
 * 直播事件监听器
 */
@Slf4j
@Component
public class RecordEventListener {
    @Value("${sh.video-save.path}")
    private String videoSavePath;

    private Map<String, DanmakuRecorder> danmakuRecorderMap = Maps.newHashMap();

    /**
     * 开始事件并启动弹幕录制
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

        File recordDir = new File(genRegPathByRegDate(event.getRecordAt(), name));
        if (!recordDir.exists()) {
            recordDir.mkdir();
        }
        File txtFile = new File(recordDir, DAMAKU_TXT_ALL_FILE);
        if (txtFile.exists()) {
            log.warn("{} record start, but txt file exists, path: {}, will skip", name, txtFile.getAbsolutePath());
            return;
        }


        log.info("{} record start, begin record danmaku, path: {}", name, txtFile.getAbsolutePath());

        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder(streamerConfig);
        recorder.init();
        recorder.start(txtFile);

        danmakuRecorderMap.put(name, recorder);
    }

    /**
     * 开始事件并启动弹幕录制
     */
    @Async
    @EventListener
    public void handleDanmakuEnd(StreamRecordEndEvent event) {
        if (!danmakuRecorderMap.containsKey(event.getStreamName())) {
            return;
        }

        log.info("{} record end, stop record danmaku", event.getStreamName());
        DanmakuRecorder recorder = danmakuRecorderMap.get(event.getStreamName());
        recorder.close();

        danmakuRecorderMap.remove(event.getStreamName());
    }


    /**
     * 生成录像保存地址
     *
     * @param recordAt
     * @return
     */
    private String genRegPathByRegDate(Date recordAt, String name) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(recordAt);

        File regFile = new File(new File(videoSavePath, name), timeV);
        return regFile.getAbsolutePath();
    }
}