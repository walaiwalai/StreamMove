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
     * 直播保存路径
     */
    private String videoSavePath;

    /**
     * 视频保存格式ts|mkv|flv|mp4|ts音频|mkv音频
     */
    private String videFormat = "mp4";


    /**
     * 分多端拉流的每段大小
     */
    private Integer segmentDuration;

    /**
     * 原画|超清|高清|标清 = 原画
     */
    private String quality;

    /**
     * 投稿时忽略小于此大小的文件(M)
     */
    private Integer videoPartLimitSize = 100;

    /**
     * b站投稿参数
     */
    private String biliCookies;
    private String accessToken;
    private Long mid;



    // *********************其他配置**********************
    /**
     * 回收文件cron表达式
     */
    private String fileCleanCron;
    private String configRefreshCron;


    // *********************cookies**********************
    private String afreecaTvCookies;
}
