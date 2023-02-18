package com.sh.engine.model.record;

import com.sh.config.model.config.StreamerInfo;
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
    private StreamerInfo streamerInfo;
    private String timeV;
    private String dirName;

    /**
     * 主播名称
     */
    private String recorderName;

    /**
     * 拉视频流的地址
     */
    private String streamUrl;
}
