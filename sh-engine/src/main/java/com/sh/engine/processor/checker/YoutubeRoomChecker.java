package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * youtube
 * @Author : caiwen
 * @Date: 2025/2/2
 */
@Component
@Slf4j
public class YoutubeRoomChecker extends AbstractRoomChecker {

    @Override
    public Recorder getStreamRecorder( StreamerConfig streamerConfig ) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            String roomUrl = streamerConfig.getRoomUrl();
            boolean isLiving = checkIsLivingByStreamLink(roomUrl);
            return isLiving ? new StreamLinkRecorder(new Date(), roomUrl) : null;
        } else {
            return recordVod(streamerConfig);
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.YOUTUBE;
    }

    private Recorder recordVod(StreamerConfig streamerConfig) {
        // 采用yt-dlp下载
        // 命令：yt-dlp 地址 -F，选择1080的视频
        // 命令：yt-dlp  地址 -f 编号 -o 输出文件名
        return null;
    }
}
