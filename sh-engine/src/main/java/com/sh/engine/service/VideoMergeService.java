package com.sh.engine.service;

import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;

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
     * 合并高光视频、添加封面，并只在高光输出中应用同一组广告蒙层。
     *
     * <p>P01、P02 等正常录制分片仅作为只读输入，不应用蒙层也不改写。</p>
     *
     * @param intervals   需要合并的高光区间
     * @param targetVideo 高光目标视频文件
     * @param title       标题
     * @param maskPlan    基于源画面归一化坐标的蒙层计划
     * @return 合并是否成功
     */
    boolean mergeHighlightWithCover(List<VideoInterval> intervals,
                                    File targetVideo,
                                    String title,
                                    HighlightMaskPlan maskPlan);

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
     * 合并为竖屏高光短视频，并只在高光输出中、竖版裁剪之前应用广告蒙层。
     *
     * <p>P01、P02 等正常录制分片仅作为只读输入，不应用蒙层也不改写。</p>
     *
     * @param intervals   需要合并的高光区间
     * @param targetVideo 高光目标视频文件
     * @param title       标题
     * @param maskPlan    基于源画面归一化坐标的蒙层计划
     * @return 合并是否成功
     */
    boolean mergeVerticalHighlightWithCover(List<VideoInterval> intervals,
                                            File targetVideo,
                                            String title,
                                            HighlightMaskPlan maskPlan);

    /**
     * ts转mp4
     *
     * @param fromVideo ts文件
     * @return 转换是否成功
     */
    boolean ts2Mp4(File fromVideo);
}
