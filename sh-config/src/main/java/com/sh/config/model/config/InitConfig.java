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
     * 用户在各个平台的账户保存地址
     */
    private String accountSavePath;

    // *********************录制配置**********************
    private Integer maxRecordingCount = 2;

    /**
     * 代理服务地址
     * 如：socks5h://address:port
     */
    private String httpProxy;

    // *********************投稿配置**********************
    /**
     * 投稿时忽略小于此大小的文件(M)
     */
    private Integer videoPartLimitSize = 100;

    // *********************twitchApi请求header Authorization**********************
    private String twitchAuthorization;

    // *********************B站视频上传**********************
    private String biliCookies;
    private String accessToken;
    private Long mid;
    // *********************阿里云云盘上传**********************
    private String refreshToken;
    private String targetFileId = "root";


    // *********************企业微信消息通知**********************
    private String weComWebhookSecret;

    /**
     * 微应用相关token
     */
    private String weComAgentId;
    private String weComSecret;
    /**
     * 消息事件token
     */
    private String weComEventToken;
    private String weComEncodingAesKey;
    private String weComCorpId;


    // *********************用户登录小关**********************
    private String phoneNumber;
    private String password;

    // *********************MINIO相关参数**********************
    private String minioUrl;
    private String minioAccessKey;
    private String minioSecretKey;

    // *********************天翼云存储相关参数**********************
    private String oosAccessKeyId;
    private String oosSecretAccessKey;
    private String oosEndpoint;
    private String oosBucketName;
    private String oosRegion;
}
