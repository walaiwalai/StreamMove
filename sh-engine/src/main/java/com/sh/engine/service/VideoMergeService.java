package com.sh.engine.service;

import com.sh.engine.model.highlight.VideoInterval;

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


    /**
     * 合并视频带封面
     *
     * @param intervals   需要合并的区间
     * @param targetVideo 目标视频文件
     * @param title       标题
     * @return 合并是否成功
     */
    boolean mergeWithCover(List<VideoInterval> intervals, File targetVideo, String title);

    /**
     * 合并为竖屏短视频并添加封面。
     *
     * <p>游戏画面只裁剪左右两侧，完整保留上下 HUD；随后等比缩放并放入
     * 1080x1920 竖屏画布。裁剪、渐变和拼接在同一次 FFmpeg 编码中完成。</p>
     *
     * @param intervals   需要合并的区间
     * @param targetVideo 目标视频文件
     * @param title       标题
     * @return 合并是否成功
     */
    boolean mergeVerticalWithCover(List<VideoInterval> intervals, File targetVideo, String title);

    /**
     * ts转mp4
     *
     * @param fromVideo ts文件
     * @return 转换是否成功
     */
    boolean ts2Mp4(File fromVideo);
}
