package com.sh.engine.model.bili;

import com.sh.config.model.video.FailedUploadVideo;
import com.sh.config.model.video.SucceedUploadSeverVideo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 21:01
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BiliVideoUploadTask {
    /**
     * 上传的主播名称（对应streamerInfo中的name）
     */
    private String streamerName;
    /**
     * 针对一个状态文件fileStatus.json下的视频目录
     */
    private String dirName;

    /**
     * 上传视频的标题（根据streamerInfo的template生成带时间）
     * 如：TheyShy直播2022-12-12
     */
    private String title;

    /**
     * 是否上传失败
     */
    private Boolean isUploadFail;

    /**
     * 上传成功的视频
     */
    private List<SucceedUploadSeverVideo> succeedUploaded;

    /**
     * 上传失败的视频
     */
    private FailedUploadVideo failUpload;

//    /**
//     * 任务开始上传时间(秒时间戳，如1677538240)
//     */
//    private Long uploadStart;
//
//    /**
//     * 上传任务的过期时间(秒时间戳，如1677538240)
//     */
//    private Long deadline;

    private BiliWebPreUploadParams biliPreUploadInfo;
}
