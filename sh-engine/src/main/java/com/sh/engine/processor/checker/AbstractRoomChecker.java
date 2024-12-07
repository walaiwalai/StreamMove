package com.sh.engine.processor.checker;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.StreamLinkCheckCmd;
import com.sh.engine.processor.recorder.Recorder;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.text.SimpleDateFormat;
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
    public abstract Recorder getStreamRecorder(StreamerConfig streamerConfig);

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
        if (StringUtils.isBlank(streamerConfig.getLastRecordTime())) {
            return true;
        }
        String lastRecordTime = streamerConfig.getLastRecordTime();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date date1 = dateFormat.parse(lastRecordTime);
            return date1.getTime() < tsRegDate.getTime();
        } catch (Exception e) {
        }
        return false;
    }

    /**
     * 生成录像保存地址
     *
     * @param date
     * @return
     */
    protected String genRegPathByRegDate(Date date) {
        String name = StreamerInfoHolder.getCurStreamerName();
        String savePath = ConfigFetcher.getInitConfig().getVideoSavePath();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(date);

        File regFile = new File(new File(savePath, name), timeV);
        return regFile.getAbsolutePath();
    }

    protected boolean checkIsLivingByStreamLink(String url) {
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("streamlink " + url);
        checkCmd.execute();

        return checkCmd.isStreamOnline();
    }
}
