package com.sh.schedule.registry;

import com.sh.config.model.config.StreamHelperConfig;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.RoomCheckWorker;

import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/1/25 11:39
 */
public class RoomCheckWorkerRegister extends ProcessWorkerRegister {

    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return RoomCheckWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        // 默认每6分钟检查一次
        return Optional.ofNullable(getShGlobalConfig())
                .map(StreamHelperConfig::getRoomCheckCron)
                .orElse("0 0/6 * * * ?");
    }

    @Override
    protected String getPrefix() {
        return "ROOM_CHECK";
    }
}
