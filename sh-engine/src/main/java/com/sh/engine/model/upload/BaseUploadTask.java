package com.sh.engine.model.upload;

import com.google.common.collect.Lists;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.model.video.SucceedUploadSeverVideo;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 03 10 11 46
 **/
@Data
@Builder
public class BaseUploadTask {
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
    private List<SucceedUploadSeverVideo> succeedUploaded = Lists.newArrayList();

    public List<RemoteSeverVideo> convertSucceedRemoteVideos() {
        return succeedUploaded.stream()
                .map(s -> new RemoteSeverVideo(s.getTitle(), s.getFilename()))
                .collect(Collectors.toList());
    }
}
