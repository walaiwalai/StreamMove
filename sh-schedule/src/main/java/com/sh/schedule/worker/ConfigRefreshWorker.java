package com.sh.schedule.worker;

import com.sh.config.manager.ConfigFetcher;
import org.quartz.JobExecutionContext;

/**
 * @Author caiwen
 * @Date 2023 12 23 10 20
 **/
public class ConfigRefreshWorker extends ProcessWorker {
    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        ConfigFetcher.refresh();
    }
}
