package com.sh.engine.website;

import com.sh.config.model.config.StreamerInfo;
import com.sh.engine.StreamChannelTypeEnum;

/**
 * @author caiWen
 * @date 2023/1/23 13:38
 */
public abstract class AbstractStreamerService {
    /**
     * 房间是否在线, 又返回对应的streamUrl
     * @param streamerInfo
     * @return
     */
    public abstract String isRoomOnline(StreamerInfo streamerInfo);

    /**
     * 流接受平台类型
     * @return
     */
    public abstract StreamChannelTypeEnum getType();
}
