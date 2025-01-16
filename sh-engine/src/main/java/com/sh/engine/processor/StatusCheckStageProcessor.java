package com.sh.engine.processor;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 人维度的check，提前返回
 * 如：录播中的状态
 *
 * @Author caiwen
 * @Date 2025 01 16 22 29
 **/
@Component
@Slf4j
public class StatusCheckStageProcessor extends AbstractStageProcessor {
    @Resource
    private StatusManager statusManager;

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        Integer maxRecordingCount = ConfigFetcher.getInitConfig().getMaxRecordingCount();

        // 直播正在录制
        boolean isLastRecording = statusManager.isRoomPathFetchStream();
        if (isLastRecording) {
            log.info("{} is recording...", name);
            throw new StreamerRecordException(ErrorEnum.FAST_END);
        }

        // 录播达到最大个数限制（直播不拦截）
        if (statusManager.count() >= maxRecordingCount) {
            if (!streamerConfig.isRecordWhenOnline()) {
                log.info("hit max recoding count, will return, name: {}.", name);
                throw new StreamerRecordException(ErrorEnum.FAST_END);
            }
        }
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.INIT;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.STATUS_CHECK;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.STATUS_CHECK_FINISH;
    }
}
