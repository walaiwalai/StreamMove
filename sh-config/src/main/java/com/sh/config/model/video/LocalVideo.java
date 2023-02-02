package com.sh.config.model.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/26 10:14
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LocalVideo {
    private boolean isFailed=false;
    private String localFileFullPath;
    private String title;
    private String desc;
    private Long fileSize;
}
