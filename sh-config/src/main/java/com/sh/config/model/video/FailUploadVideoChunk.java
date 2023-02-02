package com.sh.config.model.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/29 20:12
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FailUploadVideoChunk {
    /**
     * 当前chunk顺序
     */
    private Integer chunkNo;

    /**
     * 当前chunk视频开始穿
     */
    private Long chunkStart;

    /**
     * 当前chunk视频的大小
     */
    private Long curChunkSize;
}
