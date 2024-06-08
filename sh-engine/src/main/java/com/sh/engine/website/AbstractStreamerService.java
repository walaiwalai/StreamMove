package com.sh.engine.website;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.RecordStream;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/1/23 13:38
 */
public abstract class AbstractStreamerService {
    protected static final OkHttpClient CLIENT = new OkHttpClient().newBuilder().build();

    /**
     * 房间是否在线, 又返回对应的streamUrl
     *
     * @param streamerConfig
     * @return
     */
    public abstract RecordStream isRoomOnline(StreamerConfig streamerConfig);

    /**
     * 流接受平台类型
     *
     * @return
     */
    public abstract StreamChannelTypeEnum getType();

    protected boolean checkVodIsNew(StreamerConfig streamerConfig, String tsRegDate) {
        if (StringUtils.isBlank(streamerConfig.getLastRecordTime())) {
            return true;
        }
        String lastRecordTime = streamerConfig.getLastRecordTime();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date date1 = dateFormat.parse(lastRecordTime);
            Date date2 = dateFormat.parse(tsRegDate);
            return date1.getTime() < date2.getTime();
        } catch (Exception e) {
        }
        return false;
    }
}
