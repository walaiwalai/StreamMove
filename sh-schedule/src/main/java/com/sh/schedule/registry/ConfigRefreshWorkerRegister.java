package com.sh.schedule.registry;

import com.sh.config.manager.ConfigFetcher;
import com.sh.schedule.worker.ConfigRefreshWorker;
import com.sh.schedule.worker.ProcessWorker;

/**
 * @Author caiwen
 * @Date 2023 12 23 10 20
 **/
public class ConfigRefreshWorkerRegister extends ProcessWorkerRegister {
    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return ConfigRefreshWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        return ConfigFetcher.getInitConfig().getConfigRefreshCron();
    }

    @Override
    protected String getPrefix() {
        return "CONFIG_REFRESH";
    }
}
