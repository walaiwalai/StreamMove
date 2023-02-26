package com.sh.schedule.registry;

import com.sh.config.model.config.StreamHelperConfig;
import com.sh.schedule.worker.FileCleanWorker;
import com.sh.schedule.worker.ProcessWorker;

import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/2/1 23:15
 */
public class FileCleanWorkerRegister extends ProcessWorkerRegister {
    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return FileCleanWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        // 默认每天10点钟检查一次
        return Optional.ofNullable(getShGlobalConfig())
                .map(StreamHelperConfig::getFileCleanCron)
                .orElse("0 0 10 * * ?");
    }

    @Override
    protected String getPrefix() {
        return "FILE_CLEAN";
    }
}
