package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @Author caiwen
 * @Date 2025 04 04 22 42
 **/
@Component
@Slf4j
public class PandaliveRoomChecker extends AbstractRoomChecker {
    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, getType().getType(), roomUrl) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.PANDA_LIVE;
    }
}
