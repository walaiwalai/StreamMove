package com.sh.schedule.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.schedule.ProcessScheduler;
import com.sh.schedule.worker.ProcessWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.quartz.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author caiWen
 * @date 2023/1/25 9:58
 */
@Slf4j
public abstract class ProcessWorkerRegister {
    private static final String JOB_KEY_SUFFIX = "_JOBKEY";
    private static final String TRIGGER_SUFFIX = "_TRIGGER";

    public void registry(ProcessScheduler scheduler) {
        if (needRegistry()) {
            log.info("[ProcessWorkerRegister] detail: {}, registry...", fetchDetailInfo());
            JobDetail jobDetail = JobBuilder.newJob(getWorker())
                    .withIdentity(getJobKey())
                    .build();
            Trigger cronTrigger = TriggerBuilder.newTrigger()
                    .withIdentity(getTriggerKey())
                    .withSchedule(CronScheduleBuilder.cronSchedule(getCronExpr()).withMisfireHandlingInstructionDoNothing())
                    .build();
            scheduler.registry(jobDetail, cronTrigger);
        } else {
            log.info("[ProcessWorkerRegister] detail: {} not registry", fetchDetailInfo());
        }
    }


    private JobKey getJobKey() {
        return new JobKey(getPrefix() + JOB_KEY_SUFFIX);
    }

    private TriggerKey getTriggerKey() {
        return new TriggerKey(getPrefix() + TRIGGER_SUFFIX);
    }

    private String fetchDetailInfo() {
        return this.getClass().getSimpleName() + ", " + getJobKey() + "," + getTriggerKey() + "," + getCronExpr();
    }

    /**
     * 获取ProcessWorker类
     *
     * @return
     */
    public abstract Class<? extends ProcessWorker> getWorker();

    /**
     * 是否需要注册
     *
     * @return
     */
    protected abstract boolean needRegistry();

    /**
     * corn表达式
     *
     * @return
     */
    public abstract String getCronExpr();

    /**
     * 用于
     *
     * @return
     */
    protected abstract String getPrefix();
}
