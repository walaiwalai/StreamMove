package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Lists;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.stauts.FileStatusModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.File;
import java.util.Collection;
import java.util.List;


/**
 * @author caiWen
 * @date 2023/2/1 23:14
 */
@Slf4j
public class FileCleanWorker extends ProcessWorker {
    private final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);
    public static final Environment environment = SpringUtil.getBean(Environment.class);

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        clear();
    }

    private void clear() {
        List<File> streamerFiles = listRecordDir();
        for (File streamerFile : streamerFiles) {
            Collection<File> statusFiles = FileUtils.listFiles(streamerFile, new NameFileFilter("fileStatus.json"),
                    DirectoryFileFilter.INSTANCE);
            for (File statusFile : statusFiles) {
                String curRecordPath = statusFile.getParent();
                try {
                    FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
                    if (!fileStatusModel.allPost()) {
                        // 没有上传的
                        continue;
                    }
                    log.info("Begin to delete file {}", curRecordPath);
                    FileUtils.deleteDirectory(new File(curRecordPath));
                } catch (Exception e) {
                    log.error("fuck!, recordPath: {}", curRecordPath, e);
                }
            }
        }
    }

    private static List<File> listRecordDir() {
        String videoSavePath = environment.getProperty("sh.video-save.path");
        File dir = new File(videoSavePath);
        List<File> res = Lists.newArrayList();
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                res.add(file);
            }
        }
        return res;
    }
}
