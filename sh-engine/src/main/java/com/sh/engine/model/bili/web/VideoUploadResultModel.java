package com.sh.engine.model.bili.web;


import com.sh.config.model.video.FailUploadVideoChunk;
import com.sh.config.model.video.RemoteSeverVideo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 针对一个视频上传结果
 *
 * @author caiWen
 * @date 2023/1/30 23:13
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VideoUploadResultModel {

    /**
     * 上传失败的chunk
     */
    private List<FailUploadVideoChunk> failedChunks;

    /**
     * 如果上传成功，得到在服务器上的相关信息
     */
    private RemoteSeverVideo remoteSeverVideo;

    private boolean isComplete;

}
