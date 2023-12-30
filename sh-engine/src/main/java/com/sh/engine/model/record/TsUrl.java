package com.sh.engine.model.record;

import lombok.Builder;
import lombok.Data;

/**
 * @Author caiwen
 * @Date 2023 12 29 12 05
 **/
@Builder
@Data
public class TsUrl {
    private Integer count;
    private String tsFormatUrl;

    /**
     * 每个切片的时长（ms）
     */
    private Integer tsDuration;

    public String genTsUrl(int tsNo) {
        return String.format(tsFormatUrl, tsNo);
    }
}
