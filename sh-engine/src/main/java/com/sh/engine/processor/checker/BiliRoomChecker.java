package com.sh.engine.processor.checker;

import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

@Component
@Slf4j
public class BiliRoomChecker extends AbstractRoomChecker {
    private static final String STREAMER_INFO_REFEX = "<script>window.__NEPTUNE_IS_MY_WAIFU__=(.*?)</script><script>";
    private static final String QUALITY_REGEX = "_\\d+(?=\\.m3u8\\?)";
    private static final Map<String, String> QUALITY_MAP = Maps.newHashMap();

    static {
        QUALITY_MAP.put("10000", "");
        QUALITY_MAP.put("400", "_4000");
        QUALITY_MAP.put("250", "_2500");
        QUALITY_MAP.put("150", "_1500");
    }

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, roomUrl, true) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.BILI;
    }
}
