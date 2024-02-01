package com.sh.engine.processor;

import com.sh.engine.RecordStageEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import org.springframework.stereotype.Component;

/**
 * @Author caiwen
 * @Date 2024 01 26 21 45
 * 视频的预处理工序
 * 目前有：视频的分端、精彩剪辑
 **/
@Component
public class VideoProcessProcessor extends AbstractRecordTaskProcessor {
    @Override
    public void processInternal(RecordContext context) {
        // 1. 解析处理对应插件
        // 2. 执行每个插件进行处理
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.STREAM_RECORD_FINISH;
    }

    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.VIDEO_PROCESS;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.VIDEO_PROCESS_FINISH;
    }
}
