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
     * 下载方式，分片下载seg，块下载block
     */
    private String downloadType;

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
     * 单个视频最大合并大小（M）
     */
    private int maxMergeSize;

    /**
     * 主播整体下载流量大小（G）
     */
    private int curGSize;

    /**
     * 主播整体下载最大流量大小（G）
     */
    private int maxGSize;

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
     * 上传平台
     *
     * @see UploadPlatformEnum
     */
    private String uploadPlatforms;

    /**
     * 处理插件
     *
     * @see ProcessPluginEnum
     */
    private String processPlugins;

    /**
     * 视频标签
     */
    private String tags;

    /**
     * 环境
     */
    private String env;

    /**
     * 额外信息
     *
     * @see StreamerExtraDO
     */
    private String extra;
}
