package com.sh.config.model.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @Author : caiwen
 * @Date: 2025/1/30
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamerDO {
    private Long id;
    private Date gmtCreate;
    private Date gmtModified;

    /**
     * 直播间姓名
     */
    private String name;

    /**
     * 直播间地址
     */
    private String roomUrl;

    /**
     * 录制类型, vod为录像，living为直播
     */
    private String recordType;

    /**
     * 上次录制时间
     * vod时上一次上传的视频的中的时间
     * live时本地开始的录制时间
     */
    private Date lastRecordTime;

    /**
     * 过期时间
     */
    private Date expireTime;

    /**
     * vod时录制视频的数量
     */
    private Integer lastVodCnt;

    /**
     * 多少个频分片合成一个视频
     */
    private int segMergeCnt;

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

    private String uploadPlatforms;
    private String processPlugins;
    private String tags;

    /**
     * 额外信息
     *
     * @see StreamerExtraDO
     */
    private String extra;
}
