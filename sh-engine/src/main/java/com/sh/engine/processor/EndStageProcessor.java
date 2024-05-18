package com.sh.engine.processor;

import com.sh.engine.RecordStageEnum;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2023 12 19 00 00
 **/
@Component
public class EndStageProcessor extends AbstractRecordTaskProcessor{
    @Resource
    StatusManager statusManager;
    @Override
    public void processInternal(RecordContext context) {
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.VIDEO_UPLOAD_FINISH;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.END_HANDLE;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.END;
    }
}
