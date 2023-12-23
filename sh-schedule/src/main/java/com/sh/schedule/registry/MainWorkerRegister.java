package com.sh.schedule.registry;

import com.sh.config.manager.ConfigFetcher;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.MainWorker;


/**
 * @author caiWen
 * @date 2023/1/25 11:39
 */
public class MainWorkerRegister extends ProcessWorkerRegister {

    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return MainWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        return ConfigFetcher.getInitConfig().getRoomCheckCron();
    }

    @Override
    protected String getPrefix() {
        return "ROOM_CHECK";
    }
}
