package com.sh.engine.model.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author caiwen
 * @Date 2023 12 17 17 47
 **/
@Data
@AllArgsConstructor
@Builder
public class LivingStreamer {
    /**
     * 录播的url
     */
    private String recordUrl;
    private String flvUrl;

    /**
     * 直播用户名字
     */
    private String anchorName;
}
