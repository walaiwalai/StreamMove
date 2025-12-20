package com.sh.schedule.registry;

import com.sh.config.manager.ConfigFetcher;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.RecordProcessCheckWorker;

/**
 * @author caiWen
 * @date 2025/12/19 11:30
 */
public class RecordProcessCheckWorkerRegister extends ProcessWorkerRegister {
    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return RecordProcessCheckWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        // 每10秒检查一次
        return "0/10 * * * * ?";
    }

    @Override
    protected String getPrefix() {
        return "RECORD_PROCESS_CHECK";
    }
}