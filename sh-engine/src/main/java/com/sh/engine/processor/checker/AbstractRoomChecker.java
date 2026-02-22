package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.StreamLinkCheckCmd;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import okhttp3.OkHttpClient;

import java.util.Date;

/**
 * @author caiWen
 * @date 2023/1/23 13:38
 */
public abstract class AbstractRoomChecker {
    protected static final OkHttpClient CLIENT = new OkHttpClient().newBuilder().build();

    /**
     * 获取直播录像机
     *
     * @param streamerConfig
     * @return
     */
    public abstract StreamRecorder getStreamRecorder(StreamerConfig streamerConfig);

    /**
     * 流接受平台类型
     *
     * @return
     */
    public abstract StreamChannelTypeEnum getType();

    /**
     * 是否是最新的录像
     *
     * @param streamerConfig
     * @param tsRegDate
     * @return
     */
    protected boolean checkVodIsNew(StreamerConfig streamerConfig, Date tsRegDate) {
        if (streamerConfig.getLastRecordTime() == null) {
            return true;
        }
        Date lastRecordTime = streamerConfig.getLastRecordTime();
        return lastRecordTime.getTime() < tsRegDate.getTime();
    }

    protected boolean checkIsLivingByStreamLink(String url) {
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(url);
        checkCmd.execute(40);

        return checkCmd.isStreamOnline();
    }
}
