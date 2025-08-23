package com.sh.engine.command.callback;

import com.sh.engine.model.bili.RecordSegmentInfo;

/**
 * 视频录制过程中的回调
 *
 * @Author caiwen
 * @Date 2025 08 22 22 42
 **/
@FunctionalInterface
public interface RecordCallback {
    /**
     * 当一个视频分段录制完成时调用
     *
     * @param segmentFilePath 完成的分段文件名
     */
    void onSegmentCompleted( String segmentFilePath, boolean recordEnd );
}
