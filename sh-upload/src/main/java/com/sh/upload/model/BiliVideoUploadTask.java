package com.sh.upload.model;

import com.sh.config.model.video.FailedUploadVideo;
import com.sh.config.model.video.SucceedUploadSeverVideo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 21:01
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class BiliVideoUploadTask {
    private String appSecret;
    private Integer videoPartLimitSizeInput;
    private String streamerName;
    /**
     * 针对一个状态文件fileStatus.json下的视频目录
     */
    private String dirName;
    private Long mid;
    private String accessToken;
    private Integer copyright;
    private String cover;
    private String desc;
    private Integer noRePrint;
    private Integer openElec;
    private String source;
    private List<String> tags;
    private Integer tid;
    private String title;
    private String dynamic;
    private Boolean uploadLocalFile;
    private String recorderName;
    private Long deadline;
    private Long uploadStart;
    private List<SucceedUploadSeverVideo> succeedUploaded;
    private Boolean isUploadFail;
    private FailedUploadVideo failUpload;
    private Integer succeedUploadChunk;
    private Integer succeedTotalLength;
}
