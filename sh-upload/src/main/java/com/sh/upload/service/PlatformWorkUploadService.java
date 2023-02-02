package com.sh.upload.service;

import com.sh.config.model.video.RemoteSeverVideo;
import org.apache.http.entity.InputStreamEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * @author caiWen
 * @date 2023/1/28 19:55
 */
public interface PlatformWorkUploadService {
    /**
     * 分块上传文件
     * @param uploadChunk
     * @param chunkNo
     * @param totalChunks
     * @param curChunkSize
     * @param curChunkStart
     * @param curChunkEnd
     * @param totalSize
     * @param countDownLatch
     * @param extension
     * @return
     */
    boolean uploadChunk(InputStreamEntity uploadChunk, Integer chunkNo, Integer totalChunks,
            Long curChunkSize, Long curChunkStart, Long curChunkEnd, Long totalSize, CountDownLatch countDownLatch,
            Map<String, String> extension);

    /**
     * 完成视频分块的上传
     * @param videoName
     * @param totalChunks
     * @param extension
     * @return
     */
    boolean finishChunksUpload(String videoName, Integer totalChunks, Map<String, String> extension);

    /**
     * 上传视频
     *
     * @param streamerName
     * @param remoteSeverVideos
     * @param extension
     * @return
     */
    boolean postWork(String streamerName, List<RemoteSeverVideo> remoteSeverVideos, Map<String, String> extension);
}
