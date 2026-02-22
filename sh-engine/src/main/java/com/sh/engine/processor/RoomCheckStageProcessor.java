package com.sh.engine.processor;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.processor.checker.AbstractRoomChecker;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class RoomCheckStageProcessor extends AbstractStageProcessor {
    @Resource
    ApplicationContext applicationContext;

    Map<StreamChannelTypeEnum, AbstractRoomChecker> streamerServiceMap = Maps.newHashMap();

    @PostConstruct
    private void init() {
        Map<String, AbstractRoomChecker> beansOfType = applicationContext.getBeansOfType(AbstractRoomChecker.class);
        beansOfType.forEach((key, value) -> streamerServiceMap.put(value.getType(), value));
    }

    @Override
    public void processInternal(RecordContext context) {
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamInfo = ConfigFetcher.getStreamerInfoByName(name);

        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(streamInfo.getRoomUrl());
        if (channelEnum == null) {
            log.error("roomUrl not match any existed platform, roomUrl: {}", streamInfo.getRoomUrl());
            return;
        }

        // 检测服务
        AbstractRoomChecker streamerService;
        if (ConfigFetcher.getInitConfig().getOutApiRoomCheckPlatforms().contains(channelEnum.name())) {
            streamerService = streamerServiceMap.get(StreamChannelTypeEnum.LIVE_RECORD_API);
        } else {
            streamerService = streamerServiceMap.get(channelEnum);
        }
        if (streamerService == null) {
            log.error("streamerService is null, will skip,  roomUrl: {}", streamInfo.getRoomUrl());
            return;
        }

        // 直播录像机
        context.setStreamRecorder(streamerService.getStreamRecorder(streamInfo));
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.STATUS_CHECK_FINISH;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.ROOM_CHECK;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.ROOM_CHECK_FINISH;
    }
}
