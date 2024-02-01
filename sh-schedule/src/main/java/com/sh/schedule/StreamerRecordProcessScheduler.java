package com.sh.schedule;

import com.google.common.util.concurrent.RateLimiter;
import com.sh.schedule.config.CustomJobFactory;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Properties;

/**
 * @author caiWen
 * @date 2023/1/25 10:03
 */
@Component
@Slf4j
public class StreamerRecordProcessScheduler implements ProcessScheduler {
    @Autowired
    private CustomJobFactory customJobFactory;

    private StdSchedulerFactory schedulerFactory;
    private Scheduler scheduler;

    @PostConstruct
    public void init() {
        // 初始化调度器
        RateLimiter rateLimiter = RateLimiter.create(1);
        rateLimiter.tryAcquire()
        try {
            schedulerFactory = new StdSchedulerFactory();
            Properties props = new Properties();
            props.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("quartz.properties"));
            schedulerFactory.initialize(props);
            scheduler = schedulerFactory.getScheduler();

            // 自定义 JobFactory 使得在 Quartz Job 中可以使用 @Autowired
            scheduler.setJobFactory(customJobFactory);
            scheduler.start();
        } catch (Exception e) {
            log.error("quartz scheduler init fail", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (scheduler.isStarted() && !scheduler.isShutdown()) {
                scheduler.shutdown();
            }
        } catch (Exception e) {
            log.error("quartz scheduler shutdown fail", e);
        }
    }

    @Override
    public void registry(JobDetail jobDetail, Trigger trigger) {
        try {
            if (scheduler != null) {
                scheduler.scheduleJob(jobDetail, trigger);
            }
        } catch (Exception e) {
            log.error("quartz registry fail");
        }
    }
}
