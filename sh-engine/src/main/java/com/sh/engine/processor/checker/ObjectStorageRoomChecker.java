package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author : caiwen
 * @Date: 2025/8/23
 */
@Component
@Slf4j
public class ObjectStorageRoomChecker extends AbstractRoomChecker {
    @Override
    public Recorder getStreamRecorder( StreamerConfig streamerConfig ) {
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.OBJECT_STORAGE;
    }
}
