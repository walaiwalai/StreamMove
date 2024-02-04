package com.sh.engine.processor;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
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
    @Resource
    StatusManager statusManager;

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

        if (statusManager.isDoPostProcess(streamerName)) {
            log.info("{} is doing post process, plugin: {}.", streamerName, statusManager.getCurPostProcessType(streamerName));
            return;
        }

        // 1. 解析处理对应插件，并处理
        for (String pluginName : streamerConfig.getVideoPlugins()) {
            if (plugins.get(pluginName) == null) {
                log.info("no certain video plugin for name: {}, will skip.", pluginName);
                continue;
            }

            // 加入当前处理的插件类型
            log.info("begin running {}'s {} plugin.", streamerName, pluginName);
            statusManager.doPostProcess(streamerName, pluginName);

            boolean success = plugins.get(pluginName).process(context);
            if (success) {
                log.info("{}'s {} plugin process success!", streamerName, pluginName);
            } else {
                log.error("{}'s {} plugin process failed!", streamerName, pluginName);
            }
        }

        // 2. 移除后置处理标志位
        statusManager.finishPostProcess(streamerName);
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
