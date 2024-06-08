package com.sh.engine.model;

import com.sh.engine.model.record.RecordStream;
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

    /**
     * 正在直播的主播
     */
    private RecordStream recordStream;
}
