package com.sh.engine.service.process;

import java.io.File;
import java.util.List;

public interface VideoMergeService {
    /**
     * 合并多个视频（视频同帧数，同分辨率）
     *
     * @param mergedFps   需要合并的视频列表
     * @param targetVideo 目标视频文件
     * @return 合并是否成功
     */
    boolean concatWithSameVideo(List<String> mergedFps, File targetVideo);

    /**
     * 合并多个视频
     *
     * @param mergedFps   需要合并的视频列表
     * @param targetVideo 目标视频文件
     * @return 合并是否成功
     */
    boolean concatDiffVideos(List<String> mergedFps, File targetVideo);


    boolean concatByProtocol(List<String> mergedFileNames, File targetVideo);

    /**
     * 采用concate filter进行拼接
     *
     * @param intervals   合并视频区间
     * @param targetVideo 目标视频文件
     * @param title       视频标题
     * @return
     */
    boolean mergeMultiWithFadeV2(List<List<String>> intervals, File targetVideo, String title);
}
