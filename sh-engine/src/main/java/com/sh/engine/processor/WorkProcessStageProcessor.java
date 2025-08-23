package com.sh.engine.processor;

import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.processor.plugin.VideoProcessPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2024 01 26 21 45
 * 视频的预处理工序
 * 目前有：视频的分P合并、精彩剪辑（lol）
 **/
@Component
@Slf4j
public class WorkProcessStageProcessor extends AbstractStageProcessor {
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
        if (EnvUtil.isRecorderMode()) {
            // recorder模式不进行视频处理
            return;
        }

        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);

        // 1. 解析处理对应插件，并处理, 加上系统的对应的插件
        List<String> videoPlugins = ProcessPluginEnum.getAllPlugins(streamerConfig.getVideoPlugins());
        List<String> curRecordPaths = StreamerInfoHolder.getCurRecordPaths();
        for (String curRecordPath : curRecordPaths) {
            if (statusManager.isPathOccupied(curRecordPath, streamerName)) {
                log.info("{} is doing other process, plugin: {}.", streamerName, statusManager.getCurPostProcessType(curRecordPath));
                continue;
            }

            for (String pluginName : videoPlugins) {
                if (plugins.get(pluginName) == null) {
                    throw new StreamerRecordException(ErrorEnum.PLUGIN_NOT_EXIST);
                }

                // 加入当前处理的插件类型
                statusManager.doPostProcess(curRecordPath, pluginName);

                try {
                    plugins.get(pluginName).process(curRecordPath);
                    log.info("{}'s {} plugin process success, path: {}. ", streamerName, pluginName, curRecordPath);
                } catch (Exception e) {
                    log.error("{}'s {} plugin process failed, path: {}.", streamerName, pluginName, curRecordPath, e);
                } finally {
                    // 移除后置处理标志位
                    statusManager.finishPostProcess(curRecordPath);
                }
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
