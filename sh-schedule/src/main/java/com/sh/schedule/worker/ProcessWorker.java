package com.sh.schedule.worker;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * @author caiWen
 * @date 2023/1/25 11:21
 */
@Slf4j
public abstract class ProcessWorker implements Job {
    private String PROCESS_WORK_NAME = this.getClass().getSimpleName();

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        log.info("[ProcessWorker] {} start to work...", PROCESS_WORK_NAME);
        executeJob(jobExecutionContext);
        log.info("[ProcessWorker] {} finish work.", PROCESS_WORK_NAME);
    }

    /**
     * 真正执行工作
     * @param jobExecutionContext
     */
    protected abstract void executeJob(JobExecutionContext jobExecutionContext);
}
