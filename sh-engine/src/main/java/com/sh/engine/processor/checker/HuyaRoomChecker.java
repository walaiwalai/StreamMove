package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.danmu.OrdinaryroadDamakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamLinkStreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 参考：https://github.com/ihmily/DouyinLiveRecorder
 *
 * @author caiWen
 * @date 2023/1/23 13:39
 */
@Component
@Slf4j
public class HuyaRoomChecker extends AbstractRoomChecker {
    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkStreamRecorder(date, getType().getType(), roomUrl) : null;
    }

    @Override
    public DanmakuRecorder getDanmakuRecorder(StreamerConfig streamerConfig) {
        boolean recordDamaku = BooleanUtils.isTrue(streamerConfig.isRecordDamaku());
        return recordDamaku ? new OrdinaryroadDamakuRecorder(streamerConfig) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.HUYA;
    }
}
