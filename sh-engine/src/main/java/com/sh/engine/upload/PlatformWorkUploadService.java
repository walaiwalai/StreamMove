//package com.sh.engine.upload;
//
//import com.sh.config.model.video.LocalVideo;
//import com.sh.config.model.video.RemoteSeverVideo;
//
//import java.io.File;
//import java.util.List;
//import java.util.Map;
//
///**
// * @author caiWen
// * @date 2023/1/28 19:55
// */
//public interface PlatformWorkUploadService {
//    String getName();
//
//    boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks,
//                        Integer curChunkSize, Long curChunkStart, Map<String, String> extension);
//
//    boolean finishChunks(String finishUrl, int totalChunks, String videoName, LocalVideo localVideo, Map<String, String> extension) throws Exception;
//
//    /**
//     * 客户端上传视频
//     *
//     * @param streamerName
//     * @param remoteSeverVideos
//     * @param extension
//     * @return
//     */
//    boolean postWork(String streamerName, List<RemoteSeverVideo> remoteSeverVideos, Map<String, String> extension);
//}
