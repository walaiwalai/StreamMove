package com.sh.schedule.registry;

import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.RecordWorkingCheckWorker;

/**
 * @author caiWen
 * @date 2025/12/19 11:30
 */
public class RecordWorkingCheckWorkerRegister extends ProcessWorkerRegister {
    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return RecordWorkingCheckWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        return "0 0/10 * * * ?";
    }

    @Override
    protected String getPrefix() {
        return "RECORD_WORKING_CHECK";
    }
}