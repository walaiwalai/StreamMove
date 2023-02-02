package com.sh.config.model.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上传的视频(可能有多端视频)
 * @author caiWen
 * @date 2023/1/26 9:21
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadVideoPair {
    /**
     * 成功上传视频
     */
    private List<SucceedUploadSeverVideo> succeedUploadedVideos;

    /**
     * 上传失败的视频
     */
    private FailedUploadVideo failedUploadVideo;
}
