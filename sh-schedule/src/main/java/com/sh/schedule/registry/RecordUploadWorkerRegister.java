package com.sh.schedule.registry;

import com.sh.config.model.config.StreamHelperConfig;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.RecordUploadWorker;

import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/2/1 23:07
 */
public class RecordUploadWorkerRegister extends ProcessWorkerRegister {
    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return RecordUploadWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        // 默认每10分钟检查一次
        return Optional.ofNullable(getShGlobalConfig())
                .map(StreamHelperConfig::getRecordUploadCron)
                .orElse("0 0/10 * * * ?");
    }

    @Override
    protected String getPrefix() {
        return "RECORD_UPLOAD";
    }
}
