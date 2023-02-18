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
    /**
     * 当前录像的时间
     * 如：2023-02-12
     */
    private String timeV;

    /**
     * 视频所在的文件夹（带日期）
     * 如：...download/TheShy/2023-02-12
     */
    private String dirName;

    /**
     * 主播名称（对应streamerInfo中的name）
     */
    private String recorderName;

    /**
     * 拉视频流的地址(不是roomUrl)
     */
    private String streamUrl;
}
