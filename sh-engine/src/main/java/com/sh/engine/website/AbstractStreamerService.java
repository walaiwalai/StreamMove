package com.sh.engine.website;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import okhttp3.OkHttpClient;

/**
 * @author caiWen
 * @date 2023/1/23 13:38
 */
public abstract class AbstractStreamerService {
    protected static final OkHttpClient CLIENT = new OkHttpClient().newBuilder().build();

    /**
     * 房间是否在线, 又返回对应的streamUrl
     * @param streamerConfig
     * @return
     */
    public abstract LivingStreamer isRoomOnline(StreamerConfig streamerConfig);

    /**
     * 流接受平台类型
     * @return
     */
    public abstract StreamChannelTypeEnum getType();
}
