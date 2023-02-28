package com.sh.config.model.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/26 9:33
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FailedUploadVideo {
    /**
     * 本地视频地址（全路径）
     */
    private String localFileFullPath;

    /**
     * 上传失败的chunks
     */
    private List<FailUploadVideoChunk> failUploadVideoChunks;

    /**
     * 上次失败的参数
     */
    private String uploadUrl;
    private String completeUploadUrl;
    private String serverFileName;
}
