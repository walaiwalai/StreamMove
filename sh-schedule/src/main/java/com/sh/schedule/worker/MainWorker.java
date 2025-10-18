package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.manager.RecordStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 11:40
 */
@Slf4j
public class MainWorker extends ProcessWorker {
    private final RecordStateMachine recordStateMachine = SpringUtil.getBean(RecordStateMachine.class);
    private final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        List<StreamerConfig> streamerConfigs = ConfigFetcher.getStreamerInfoList();
        for (StreamerConfig streamerConfig : streamerConfigs) {
            recordStateMachine.start(streamerConfig);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        String info = statusManager.printInfo();
        log.info(info);
    }
}
