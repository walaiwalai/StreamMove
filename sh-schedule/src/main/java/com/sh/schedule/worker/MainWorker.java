package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.manager.RecordStateMachine;
import org.quartz.JobExecutionContext;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 11:40
 */
public class MainWorker extends ProcessWorker {
    private final RecordStateMachine recordStateMachine = SpringUtil.getBean(RecordStateMachine.class);
    private final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        List<StreamerConfig> streamerConfigs = ConfigFetcher.getStreamerInfoList();
        for (StreamerConfig streamerConfig : streamerConfigs) {
            recordStateMachine.start(streamerConfig);
        }

        statusManager.printInfo();
    }
}
