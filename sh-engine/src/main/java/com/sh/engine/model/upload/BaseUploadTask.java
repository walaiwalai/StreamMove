package com.sh.engine.model.upload;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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
     * 视频标签
     */
    private List<String> tags;
}
