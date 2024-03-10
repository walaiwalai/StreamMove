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
    /**
     * 单个视频是否上传成功，如果成功会出现在BaseUploadTask的succeedUploaded中
     */
    private boolean isUpload;
    private String localFileFullPath;

    /**
     * 文件前缀，不带mp4字样
     */
    private String title;
    private Long fileSize;
}
