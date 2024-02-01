package com.sh.schedule.worker;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.processor.RecordStateMachine;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 11:40
 */
public class MainWorker extends ProcessWorker {
    @Autowired
    RecordStateMachine recordStateMachine;

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        List<StreamerConfig> streamerConfigs = ConfigFetcher.getStreamerInfoList();
        for (StreamerConfig streamerConfig : streamerConfigs) {
            recordStateMachine.start(streamerConfig);
        }
    }
}
