package com.sh.engine.model;

import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 03
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RecordContext {
    /**
     * 当前状态
     */
    private RecordTaskStateEnum state;

    /**
     * 对应的录像机
     */
    private StreamRecorder streamRecorder;
}
