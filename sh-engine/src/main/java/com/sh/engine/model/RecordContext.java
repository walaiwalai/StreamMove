package com.sh.engine.model;

import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.processor.recorder.Recorder;
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
    private RecordTaskStateEnum state;

    private Recorder recorder;
}
