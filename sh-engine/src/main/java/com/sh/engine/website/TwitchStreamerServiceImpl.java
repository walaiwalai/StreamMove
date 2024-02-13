package com.sh.engine.website;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TwitchStreamerServiceImpl extends AbstractStreamerService {
    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCH;
    }
}
