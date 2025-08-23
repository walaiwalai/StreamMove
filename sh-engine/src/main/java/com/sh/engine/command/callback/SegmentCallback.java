package com.sh.engine.command.callback;

/**
 * 视频分段录制完成回调接口
 *
 * @Author caiwen
 * @Date 2025 08 22 22 42
 **/
@FunctionalInterface
public interface SegmentCallback {
    /**
     * 当一个视频分段录制完成时调用
     *
     * @param segmentFilePath 完成的分段文件名
     */
    void onSegmentCompleted(String segmentFilePath);
}
