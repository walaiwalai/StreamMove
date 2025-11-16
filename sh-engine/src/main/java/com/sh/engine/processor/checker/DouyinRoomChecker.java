package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.danmu.OrdinaryroadDamakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamLinkStreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @Author caiwen
 * @Date 2025 01 12 12 24
 **/
@Component
@Slf4j
public class DouyinRoomChecker extends AbstractRoomChecker {
    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkStreamRecorder(date, getType().getType(), roomUrl) : null;
    }

    @Override
    public DanmakuRecorder getDanmakuRecorder(StreamerConfig streamerConfig) {
        return new OrdinaryroadDamakuRecorder(streamerConfig);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.DOU_YIN;
    }
}
