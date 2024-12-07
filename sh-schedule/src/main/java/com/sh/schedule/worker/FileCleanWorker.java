package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.base.Streamer;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.quartz.JobExecutionContext;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;


/**
 * @author caiWen
 * @date 2023/2/1 23:14
 */
@Slf4j
public class FileCleanWorker extends ProcessWorker {
    private final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        List<StreamerConfig> streamerConfigs = ConfigFetcher.getStreamerInfoList();
        for (StreamerConfig streamerConfig : streamerConfigs) {
            init(streamerConfig);
            for (String curRecordPath : StreamerInfoHolder.getCurRecordPaths()) {
                FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
                if (!fileStatusModel.allPost()) {
                    // 没有上传的
                    continue;
                }

                if (statusManager.isPathOccupied(curRecordPath)) {
                    continue;
                }

                log.info("Begin to delete file {}", curRecordPath);
                try {
                    FileUtils.deleteDirectory(new File(curRecordPath));
                } catch (IOException e) {
                    log.error("fuck!", e);
                } finally {
                    StreamerInfoHolder.clear();
                }
            }
        }
    }

    private void init(StreamerConfig streamerConfig) {
        String name = streamerConfig.getName();
        List<String> recordPaths = Lists.newArrayList();
        File streamerFile = new File(ConfigFetcher.getInitConfig().getVideoSavePath(), name);
        if (streamerFile.exists()) {
            Collection<File> statusFiles = FileUtils.listFiles(streamerFile, new NameFileFilter("fileStatus.json"),
                    DirectoryFileFilter.INSTANCE);
            for (File statusFile : statusFiles) {
                recordPaths.add(statusFile.getParent());
            }
        }

        // threadLocal
        Streamer streamer = new Streamer();
        streamer.setName(name);
        streamer.setRecordPaths(recordPaths);
        StreamerInfoHolder.addStreamer(streamer);
    }
}
