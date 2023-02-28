package com.sh.upload.service;

import com.sh.config.model.video.RemoteSeverVideo;
import org.apache.http.entity.InputStreamEntity;

import java.io.File;
import java.util.List;
import java.util.Map;

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
     * @param extension
     * @return
     */
    boolean uploadChunkOnWeb(InputStreamEntity uploadChunk, Integer chunkNo, Integer totalChunks,
            Long curChunkSize, Long curChunkStart, Long curChunkEnd, Long totalSize, Map<String, String> extension);

    /**
     * 完成视频分块的上传
     * @param videoName
     * @param totalChunks
     * @param extension
     * @return
     */
    boolean finishChunksUploadOnWeb(String videoName, Integer totalChunks, Map<String, String> extension);

    /**
     * 上传视频
     *
     * @param streamerName
     * @param remoteSeverVideos
     * @param extension
     * @return
     */
    boolean postWorkOnWeb(String streamerName, List<RemoteSeverVideo> remoteSeverVideos, Map<String, String> extension);


    /**
     * 客户端视频分块上传
     * @param uploadUrl
     * @param targetFile
     * @param chunkNo
     * @param totalChunks
     * @param curChunkSize
     * @param curChunkStart
     * @param extension
     * @return
     */
    boolean uploadChunkOnClient(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks,
            Integer curChunkSize, Long curChunkStart, Map<String, String> extension);


    /**
     * 客户端上传视频
     * @param streamerName
     * @param remoteSeverVideos
     * @param extension
     * @return
     */
    public boolean postWorkOnClient(String streamerName, List<RemoteSeverVideo> remoteSeverVideos,
            Map<String, String> extension);
}
