package com.sh.engine.model.upload;

import lombok.Data;

import java.util.List;

/**
 * 上传的元数据
 * @Author caiwen
 * @Date 2024 09 28 22 29
 **/
@Data
public class UploadMetaData {
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
