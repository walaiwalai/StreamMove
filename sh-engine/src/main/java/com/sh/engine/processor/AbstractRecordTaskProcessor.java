package com.sh.engine.processor;

import com.sh.engine.RecordStageEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;


/**
 * @Author caiwen
 * @Date 2023 12 18 22 53
 **/
public abstract class AbstractRecordTaskProcessor {
    public void process(RecordContext context) {
        checkState(context.getState());
        processInternal(context);
        context.setState(targetState());
    }

    private void checkState(RecordTaskStateEnum state) {
        if (acceptState() != state) {
            throw new RuntimeException("internal error, accept error state, accept:" + state + "required: " + acceptState());
        }
    }


    public abstract void processInternal(RecordContext context);

    public abstract RecordTaskStateEnum acceptState();

    public abstract RecordStageEnum getStage();

    public abstract RecordTaskStateEnum targetState();
}
