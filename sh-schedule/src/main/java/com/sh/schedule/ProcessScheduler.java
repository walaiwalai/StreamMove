package com.sh.schedule;

import org.quartz.JobDetail;
import org.quartz.Trigger;

/**
 * @author caiWen
 * @date 2023/1/25 10:02
 */
public interface ProcessScheduler {
    /**
     * 将任务注册到quartz调度器
     * @param jobDetail
     * @param trigger
     */
    void registry(JobDetail jobDetail, Trigger trigger);
}
