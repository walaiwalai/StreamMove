package com.sh.config.model.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/26 13:04
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadVideoPartResult {
    private String uploadUrl;

    private String completeUploadUrl;

    private String serverFileName;
}
