package com.sh.engine.processor;

import com.google.common.collect.Lists;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 37
 **/
@Component
public class ErrorStageProcessor extends AbstractRecordTaskProcessor {
    @Override
    public void processInternal(RecordContext context) {

    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.ERROR;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.ERROR_HANDLE;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.END;
    }
}
