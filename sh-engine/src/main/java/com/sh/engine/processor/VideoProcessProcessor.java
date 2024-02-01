package com.sh.engine.processor;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.plugin.VideoProcessPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2024 01 26 21 45
 * 视频的预处理工序
 * 目前有：视频的分P合并、精彩剪辑（lol）
 **/
@Component
@Slf4j
public class VideoProcessProcessor extends AbstractRecordTaskProcessor {
    @Resource
    ApplicationContext applicationContext;

    Map<String, VideoProcessPlugin> plugins = Maps.newHashMap();
    @PostConstruct
    private void init() {
        Map<String, VideoProcessPlugin> beansOfType = applicationContext.getBeansOfType(VideoProcessPlugin.class);
        beansOfType.forEach((key, value) -> plugins.put(value.getPluginName(), value));
    }

    @Override
    public void processInternal(RecordContext context) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        if (CollectionUtils.isEmpty(streamerConfig.getVideoPlugins())) {
            return;
        }
        // 1. 解析处理对应插件，并处理
        for (String pluginName : streamerConfig.getVideoPlugins()) {
            if (plugins.get(pluginName) == null) {
                log.info("no certain video plugin for name: {}, will skip.", pluginName);
                continue;
            }
            boolean success = plugins.get(pluginName).process();
            if (success) {
                log.info("process plugin: {} success!", pluginName);
            } else {
                log.error("process plugin: {} failed!", pluginName);
            }
        }
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.STREAM_RECORD_FINISH;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.VIDEO_PROCESS;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.VIDEO_PROCESS_FINISH;
    }
}
