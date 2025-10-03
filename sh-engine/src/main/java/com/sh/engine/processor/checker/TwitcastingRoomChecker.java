package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.YtDlpVideoJsonCmd;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamLinkStreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.VodM3U8StreamRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Slf4j
public class TwitcastingRoomChecker extends AbstractRoomChecker {
    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchLivingRecord(streamerConfig);
        } else {
            return fetchVodRecord(streamerConfig);
        }
    }

    @Override
    public DanmakuRecorder getDanmakuRecorder(StreamerConfig streamerConfig) {
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCASTING;
    }

    private StreamRecorder fetchLivingRecord(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkStreamRecorder(date, getType().getType(), roomUrl) : null;
    }

    private StreamRecorder fetchVodRecord(StreamerConfig streamerConfig) {
        String vodUrl = streamerConfig.getRoomUrl();
        YtDlpVideoJsonCmd ytDlpVideoJsonCmd = new YtDlpVideoJsonCmd(vodUrl);
        ytDlpVideoJsonCmd.execute(20);

        JSONObject meta = ytDlpVideoJsonCmd.getMeta();
        Date regDate = new Date(meta.getLong("timestamp") * 1000L);
        if (!checkVodIsNew(streamerConfig, regDate)) {
            return null;
        }

        return new VodM3U8StreamRecorder(regDate, getType().getType(), vodUrl);
    }
}
