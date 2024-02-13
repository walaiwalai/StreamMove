package com.sh.engine.model.record;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * @Author caiwen
 * @Date 2023 12 29 12 05
 **/
@Builder
@Data
public class TsRecordInfo {
    private Integer count;
    private String tsFormatUrl;

    /**
     * 记录时间
     */
    private Date regDate;

    public String genTsUrl(int tsNo) {
        return String.format(tsFormatUrl, tsNo);
    }
}
