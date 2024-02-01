package com.sh.engine.model.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/23 14:28
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RecordTask {
    /**
     * 当前录像的时间
     * 如：2023-02-12晚上
     */
    private String timeV;

    /**
     * 主播名称（对应streamerInfo中的name）
     */
    private String recorderName;

    /**
     * 拉视频流的地址(不是roomUrl)
     */
    private String streamUrl;

    /**
     * 视频切片地址
     */
    private TsUrl tsUrl;
}
