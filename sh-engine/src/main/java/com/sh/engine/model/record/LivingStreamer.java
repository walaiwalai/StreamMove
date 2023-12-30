package com.sh.engine.model.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * @Author caiwen
 * @Date 2023 12 17 17 47
 **/
@Data
@AllArgsConstructor
@Builder
public class LivingStreamer {
    /**
     * 直播用户名字
     */
    private String anchorName;

    /**
     * living/ts
     */
    private String type;

    /**
     * 录播的url
     */
    private String streamUrl;

    /**
     * 切片详情
     */
    private TsUrl tsUrl;
}
