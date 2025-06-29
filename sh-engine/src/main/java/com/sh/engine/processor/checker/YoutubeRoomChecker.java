package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.YtDlpPlaylistProcessCmd;
import com.sh.engine.model.ffmpeg.YtDlpVideoMetaProcessCmd;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.processor.recorder.YtDlpRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * youtube
 *
 * @Author : caiwen
 * @Date: 2025/2/2
 */
@Component
@Slf4j
public class YoutubeRoomChecker extends AbstractRoomChecker {

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            String roomUrl = streamerConfig.getRoomUrl();
            boolean isLiving = checkIsLivingByStreamLink(roomUrl);
            return isLiving ? new StreamLinkRecorder(new Date(), getType().getType(), roomUrl) : null;
        } else {
            return recordVod(streamerConfig);
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.YOUTUBE;
    }

    private Recorder recordVod(StreamerConfig streamerConfig) {
        // 获取视频列表前N个
        int lastVodCnt = Math.max(streamerConfig.getLastVodCnt(), 1);
        YtDlpPlaylistProcessCmd playlistCmd = new YtDlpPlaylistProcessCmd(streamerConfig.getRoomUrl(), lastVodCnt);
        playlistCmd.execute(30);

        List<String> videoUrls = playlistCmd.getVideoUrls();
        if (CollectionUtils.isEmpty(videoUrls)) {
            return null;
        }

        YtDlpVideoMetaProcessCmd metaCmd = new YtDlpVideoMetaProcessCmd(videoUrls);
        metaCmd.execute(videoUrls.size() * 20L);
        List<YtDlpVideoMetaProcessCmd.YtDlpVideoMeta> videoMetas = metaCmd.getVideoMetaMap();

        // 时间戳从小到大排序
        videoMetas = videoMetas.stream()
                .sorted(Comparator.comparingLong(YtDlpVideoMetaProcessCmd.YtDlpVideoMeta::getUploadTimeStamp))
                .collect(Collectors.toList());
        for (YtDlpVideoMetaProcessCmd.YtDlpVideoMeta videoMeta : videoMetas) {
            Date regDate = new Date(videoMeta.getUploadTimeStamp());
            if (checkVodIsNew(streamerConfig, regDate)) {
                return new YtDlpRecorder(regDate, getType().getType(), videoMeta.getVideoUrl());
            }
        }

        return null;
    }
}
