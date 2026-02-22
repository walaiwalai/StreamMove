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
     * 过期时间
     */
    private Date expireTime;


    // -------------------------------视频录制相关--------------------------------
    /**
     * 直播间地址
     */
    private String roomUrl;

    /**
     * 原始直播间地址
     */
    private String originalRoomUrl;

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
     * 录制模式
     * t_3600表示按照时间间隔录制，一个视频3600
     * s_2048表示按照视频大小录制，一个视频2048
     */
    private String recordMode;

    /**
     * 录制格式
     * mp4/ts
     */
    private String recordFormat;

    /**
     * 针对录制录像的特定链接
     */
    private List<String> certainVodUrls;

    /**
     * 是否只要音频
     */
    private boolean onlyAudio;

    /**
     * 是否录制弹幕
     */
    private boolean recordDamaku;

    /**
     * 录制质量
     * 0表示最好, 依次递减
     */
    private Integer recordQuality;

    /**
     * 当前流量（G）
     */
    private Float curTrafficGB;

    /**
     * 最大流量（G）
     */
    private Float maxTrafficGB;

    // -------------------------------视频录制结尾--------------------------------





    // -------------------------------视频处理相关--------------------------------
    /**
     * 视频处理插件
     */
    private List<String> videoPlugins;

    /**
     * b站投稿开片动画
     */
    private List<String> biliOpeningAnimations;
    // -------------------------------视频处理结尾--------------------------------






    // -----------------------------------视频作品上传相关----------------------------------------
    /**
     * 视频上传平台
     */
    private List<String> uploadPlatforms;

    /**
     * 指定的b站上传cookies
     */
    private String certainBiliCookies;

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
     * 投稿封面
     */
    private String cover;

    /**
     * 抖音投稿相关
     */
    private String location;

    // -------------------------------AI高光剪辑相关--------------------------------
    /**
     * DeepSeek API Key for AI highlight analysis
     */
    private String deepSeekApiKey;

    /**
     * ASR service provider (aliyun/xunfei/whisper/none)
     */
    private String asrProvider = "none";

    /**
     * ASR service API Key
     */
    private String asrApiKey;

    /**
     * AI highlight minimum score threshold (1-10)
     */
    private int aiHighlightMinScore = 7;
    // -------------------------------AI高光剪辑结尾--------------------------------
}
