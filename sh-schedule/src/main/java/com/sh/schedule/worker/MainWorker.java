package com.sh.schedule.worker;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.processor.RecordStateMachine;
import com.sh.engine.service.RoomCheckService;
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
        List<StreamerInfo> streamerInfos = ConfigFetcher.getStreamerInfoList();
        for (StreamerInfo streamerInfo : streamerInfos) {
            RecordContext context = new RecordContext();
            context.setName(streamerInfo.getName());
            context.setState(RecordTaskStateEnum.INIT);
            recordStateMachine.start(context);
        }
    }
}
