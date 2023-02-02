package com.sh.schedule.worker;

import com.sh.engine.service.RoomCheckService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author caiWen
 * @date 2023/1/25 11:40
 */
public class RoomCheckWorker extends ProcessWorker {
    @Autowired
    RoomCheckService roomCheckService;

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        roomCheckService.check();
    }
}
