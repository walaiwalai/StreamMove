package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author caiwen
 * @Date 2023 12 17 16 31
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InitConfig {
    // **********************录制配置**********************
    /**
     * 房间检查时间cron表达式
     */
    private String roomCheckCron;
    /**
     * 回收文件cron表达式
     */
    private String fileCleanCron;
    /**
     * 刷新配置cron
     */
    private String configRefreshCron;

    /**
     * 直播保存路径
     */
    private String videoSavePath;

    /**
     * 视频保存格式ts|mkv|flv|mp4|ts音频|mkv音频
     */
    private String videFormat = "mp4";

    /**
     * 原画|超清|高清|标清 = 原画
     */
    private String quality;

    private Integer maxRecordingCount = 2;

    // *********************B站视频上传**********************
    private String biliCookies;
    private String accessToken;
    private Long mid;
    // *********************阿里云云盘上传**********************
    private String refreshToken;
    private String targetFileId = "root";
    // *********************直播cookies**********************
    private String afreecaTvCookies;
    // *********************直播cookies**********************
    private String huyaCookies;
    // *********************twitchcookies**********************
    private String twitchCookies;
    // *********************其他配置**********************
    /**
     * 投稿时忽略小于此大小的文件(M)
     */
    private Integer videoPartLimitSize = 100;

    // *********************消息通知**********************
    private String weComSecret;
}
