package com.sh.schedule.worker;

import com.sh.upload.service.RecordUploadService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author caiWen
 * @date 2023/2/1 23:07
 */
public class RecordUploadWorker extends ProcessWorker {
    @Autowired
    RecordUploadService recordUploadService;

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {

    }
}
