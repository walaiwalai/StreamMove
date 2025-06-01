package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 主播配置= 主播信息+主播作品信息
 *
 * @author caiWen
 * @date 2023/1/23 10:45
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamerConfig {
    /**
     * 直播间姓名
     */
    private String name;

    /**
     * 直播间地址
     */
    private String roomUrl;

    /**
     * 是否直播时录制
     * true直播录制，false直播完成录制录像
     */
    private boolean recordWhenOnline;

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
    private int lastVodCnt;

    /**
     * 视频处理插件
     */
    private List<String> videoPlugins;

    /**
     * 视频上传平台
     */
    private List<String> uploadPlatforms;

    /**
     * 视频标题模板
     */
    private String templateTitle;

    /**
     * 视频描述
     */
    private String desc;

    /**
     * 视频标签
     */
    private List<String> tags;

    /**
     * 一个视频分片大小（M）
     */
    private Integer maxMergeSize;

    /**
     *  当前流量（G）
     */
    private Float curTrafficGB;

    /**
     * 最大流量（G）
     */
    private Float maxTrafficGB;

    /**
     * 视频封面
     */
    private String coverFilePath;

    /**
     * b站来源
     */
    private String source;

    /**
     * b站投稿分区
     */
    private Integer tid;

    /**
     * b站投稿封面
     */
    private String cover;

    /**
     * b站投稿开片动画
     */
    private List<String> biliOpeningAnimations;

    /**
     * 抖音投稿相关
     */
    private String location;

    /**
     * 针对录制录像的特定链接
     */
    private List<String> certainVodUrls;

    /**
     * 是否只要音频
     */
    private boolean onlyAudio;
}
