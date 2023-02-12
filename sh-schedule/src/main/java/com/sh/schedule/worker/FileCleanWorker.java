package com.sh.schedule.worker;

import com.alibaba.fastjson.JSON;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.manager.StatusManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import static com.sh.config.constant.StreamHelperConstant.RECORD_ROOT_PATH;

/**
 * @author caiWen
 * @date 2023/2/1 23:14
 */
@Slf4j
public class FileCleanWorker extends ProcessWorker {
    @Autowired
    StatusManager statusManager;


    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        Collection<File> files = FileUtils.listFiles(new File(RECORD_ROOT_PATH), new String[]{"json"}, true);
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        for (File file : files) {
            try {
                FileStatusModel fileStatusModel = JSON.parseObject(IOUtils.toString(file.toURI(), "utf-8"),
                        FileStatusModel.class);
                if (StringUtils.isBlank(fileStatusModel.getPath())) {
                    continue;
                }
                if (statusManager.isRecordOnSubmission(fileStatusModel.getPath())) {
                    continue;
                }
                if (statusManager.isRoomPathFetchStream(file.getPath())) {
                    continue;
                }

                log.info("Begin to delete file {}", fileStatusModel.getPath());
                FileUtils.deleteDirectory(new File(fileStatusModel.getPath()));
            } catch (IOException e) {
                log.error("fuck!", e);
            }
        }
    }
}
