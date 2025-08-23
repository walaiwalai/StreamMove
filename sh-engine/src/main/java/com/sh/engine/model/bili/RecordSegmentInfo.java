package com.sh.engine.model.bili;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * @Author : caiwen
 * @Date: 2025/8/23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordSegmentInfo {
    /**
     * 录制阶段
     * recording/finished
     */
    private String recordStage;

    /**
     * 已经完成录制的片段，存储的相对路径
     */
    private List<String> completedSegments;

    /**
     * 开始录制时间
     */
    private Long recordTime;

    public boolean isRecordEnd() {
        return "finished".equals(recordStage);
    }

    public static RecordSegmentInfo buildFinishSegs( List<String> completedSegments ) {
        RecordSegmentInfo recordSegmentInfo = new RecordSegmentInfo();
        recordSegmentInfo.setRecordStage("finished");
        recordSegmentInfo.setCompletedSegments(completedSegments);
        return recordSegmentInfo;
    }

    public static RecordSegmentInfo buildRecordingSegs( List<String> completedSegments ) {
        RecordSegmentInfo recordSegmentInfo = new RecordSegmentInfo();
        recordSegmentInfo.setRecordStage("recording");
        recordSegmentInfo.setCompletedSegments(completedSegments);
        return recordSegmentInfo;
    }

}
