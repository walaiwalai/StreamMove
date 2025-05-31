package com.sh.config.model.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 录播上传作品配置
 *
 * @Author caiwen
 * @Date 2025 05 31 09 31
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamerWorkDO {
    private Long id;
    private Date gmtCreate;
    private Date gmtModified;

    /**
     * 直播间姓名
     */
    private String name;

    /**
     * 视频标题模板
     */
    private String templateTitle;

    /**
     * 视频封面
     */
    private String coverPath;

    /**
     * 视频描述
     */
    private String desc;

    /**
     * 视频标签
     */
    private String tags;

    /**
     * 额外信息
     *
     * @see StreamerUploadExtraDO
     */
    private String extra;

}
