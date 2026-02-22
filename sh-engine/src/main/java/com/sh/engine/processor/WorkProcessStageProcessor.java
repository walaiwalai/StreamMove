package com.sh.engine.processor;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.processor.plugin.VideoProcessPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

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
    Map<String, Semaphore> pluginSemaphoreMap = Maps.newHashMap();

    @PostConstruct
    private void init() {
        Map<String, VideoProcessPlugin> beansOfType = applicationContext.getBeansOfType(VideoProcessPlugin.class);
        beansOfType.forEach((key, value) -> plugins.put(value.getPluginName(), value));

        beansOfType.forEach(( key, value ) -> pluginSemaphoreMap.put(value.getPluginName(), new Semaphore(value.getMaxProcessParallel(), true)));
    }

    @Override
    public void processInternal(RecordContext context) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);

        // 1. 解析处理对应插件，并处理, 加上系统的对应的插件
        List<String> videoPlugins = ProcessPluginEnum.getAllPlugins(streamerConfig.getVideoPlugins());
        List<String> curRecordPaths = StreamerInfoHolder.getCurRecordPaths();
        for (String curRecordPath : curRecordPaths) {
            if (!FileUtil.exist(curRecordPath)) {
                log.error("{}'s record path not exist, maybe deleted, path: {}", streamerName, curRecordPath);
                continue;
            }
            if (statusManager.isPathOccupied(curRecordPath, streamerName)) {
                log.info("{} is doing other process, plugin: {}.", streamerName, statusManager.getCurPostProcessType(curRecordPath));
                continue;
            }

            FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
            if (fileStatusModel == null) {
                log.error("fileStatus not exist, maybe deleted, path: {}", curRecordPath);
                continue;
            }

            for (String pluginName : videoPlugins) {
                ProcessPluginEnum pluginEnum = ProcessPluginEnum.of(pluginName);
                if (plugins.get(pluginName) == null || pluginEnum == null) {
                    continue;
                }
                if (fileStatusModel.isFinishedPlugin(pluginName)) {
                    log.info("{}'s {} plugin has been processed, path: {}.", streamerName, pluginName, curRecordPath);
                    continue;
                }

                log.info("{}'s {} plugin begin processing, path: {}.", streamerName, pluginName, curRecordPath);
                // 加入当前处理的插件类型
                statusManager.doPostProcess(curRecordPath, pluginName);
                try {
                    boolean acquired = pluginSemaphoreMap.get(pluginName).tryAcquire();
                    if (!acquired) {
                        throw new StreamerRecordException(ErrorEnum.PROCESS_LATER);
                    }

                    try {
                        plugins.get(pluginName).process(curRecordPath);

                        fileStatusModel.finishPlugin(pluginName);
                        fileStatusModel.writeSelfToFile(curRecordPath);
                        log.info("{}'s {} plugin process success, path: {}. ", streamerName, pluginName, curRecordPath);
                    } catch (Exception e) {
                        log.error("{}'s {} plugin process failed, path: {}.", streamerName, pluginName, curRecordPath, e);
                        if (pluginEnum.isSystem()) {
                            throw e;
                        }
                    } finally {
                        pluginSemaphoreMap.get(pluginName).release();
                    }
                } finally {
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
