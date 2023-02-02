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
     * 上传视频的url
     */
    @Deprecated
    private String uploadUrl;


    @Deprecated
    private String completeUploadUrl;

    /**
     * 上传服务器端的视频名称
     */
    @Deprecated
    private String serverFileName;


    @Deprecated
    private Long uploadStartTime;

    private Long deadline;

    /**
     * 失败视频中成功的chunk数量
     */
    @Deprecated
    private Integer succeedUploadChunkCount;

    @Deprecated
    private Integer succeedTotalLength;

    /**
     * 上传失败的chunks
     */
    private List<FailUploadVideoChunk> failUploadVideoChunks;
}
