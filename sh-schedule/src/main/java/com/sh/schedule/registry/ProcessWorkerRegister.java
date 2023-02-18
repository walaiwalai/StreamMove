package com.sh.schedule.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.constant.StreamHelperConstant;
import com.sh.config.model.config.StreamHelperConfig;
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
    private StreamHelperConfig streamHelperConfig;

    public void loadGlobalConfig() {
        // 加载一下全局配置
        if (streamHelperConfig != null) {
            return;
        }
        try {
            log.info("try to load global config...");
            File file = new File(StreamHelperConstant.APP_PATH, "info.json");
            String configStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            JSONObject configObj = JSON.parseObject(configStr);
            streamHelperConfig = configObj.getJSONObject("streamerHelper").toJavaObject(StreamHelperConfig.class);
            log.info("load global config success");
        } catch (IOException e) {
            log.error("load global config error", e);
        }
    }

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

    protected StreamHelperConfig getShGlobalConfig() {
        return this.streamHelperConfig;
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
