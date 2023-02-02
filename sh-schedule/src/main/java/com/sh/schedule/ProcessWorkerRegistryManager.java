package com.sh.schedule;

import com.sh.schedule.registry.ProcessWorkerRegister;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * @author caiWen
 * @date 2023/1/25 9:54
 */
@Component
@Slf4j
public class ProcessWorkerRegistryManager {
    @Resource
    StreamerHelperProcessScheduler streamerHelperProcessScheduler;

    @PostConstruct
    public void init() {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                // 注册相关workerRegistry
                ServiceLoader<ProcessWorkerRegister> registers = ServiceLoader.load(ProcessWorkerRegister.class,
                        ProcessWorkerRegister.class.getClassLoader());
                Iterator<ProcessWorkerRegister> iterator = registers.iterator();
                while (iterator.hasNext()) {
                    ProcessWorkerRegister register = iterator.next();
                    register.loadGlobalConfig();
                    if (!checkRegister(register)) {
                        log.warn("check register fail");
                        continue;
                    }
                    register.registry(streamerHelperProcessScheduler);
                }
                return null;
            }
        });
    }

    private boolean checkRegister(ProcessWorkerRegister register) {
        // 是否有worker
        if (register.getWorker() == null) {
            return false;
        }

        // cron表达式是否合法
        if (!CronExpression.isValidExpression(register.getCronExpr())) {
            return false;
        }
        return true;
    }
}
