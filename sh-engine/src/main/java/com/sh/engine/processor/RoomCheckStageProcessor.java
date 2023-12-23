package com.sh.engine.processor;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.website.AbstractStreamerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 29
 **/
@Component
public class RoomCheckStageProcessor extends AbstractRecordTaskProcessor {
    private static final Logger log = LoggerFactory.getLogger(RoomCheckStageProcessor.class);
    @Resource
    ApplicationContext applicationContext;

    Map<StreamChannelTypeEnum, AbstractStreamerService> streamerServiceMap = Maps.newHashMap();
    @PostConstruct
    private void init() {
        Map<String, AbstractStreamerService> beansOfType = applicationContext.getBeansOfType(AbstractStreamerService.class);
        beansOfType.forEach((key, value) -> streamerServiceMap.put(value.getType(), value));
    }

    @Override
    public void processInternal(RecordContext context) {
        String name = context.getName();
        StreamerInfo streamInfo = ConfigFetcher.getStreamerInfoByName(name);

        // 1. 检查直播间是否开播
        context.setLivingStreamer(fetchStreamer(streamInfo));
    }

    /**
     * 获取直播间的视频推送刘
     * @param streamerInfo
     * @return
     */
    private LivingStreamer fetchStreamer(StreamerInfo streamerInfo) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(streamerInfo.getRoomUrl());
        if (channelEnum == null) {
            log.error("roomUrl not match any platform, roomUrl: {}", streamerInfo.getRoomUrl());
            return null;
        }
        AbstractStreamerService streamerService = streamerServiceMap.get(channelEnum);
        if (streamerService == null) {
            log.error("streamerService is null, type: {}", channelEnum.getDesc());
            return null;
        }
        return streamerService.isRoomOnline(streamerInfo);
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.INIT;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.CHECK_ROOM;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.ROOM_CHECK_FINISH;
    }
}
