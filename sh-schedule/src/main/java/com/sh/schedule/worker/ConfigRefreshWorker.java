package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.manager.StatusManager;
import org.quartz.JobExecutionContext;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2023 12 23 10 20
 **/
public class ConfigRefreshWorker extends ProcessWorker {
    private final ConfigFetcher configFetcher = SpringUtil.getBean(ConfigFetcher.class);

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        configFetcher.refresh();
    }
}
