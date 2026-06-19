package com.sh.config.model.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Author : caiwen
 * @Date: 2025/1/30
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamerExtraDO {
    /**
     * b站上传信息
     */
    private BiliUploadInfoDO biliUploadInfo;

    /**
     * 抖音上传信息
     */
    private DouyinUploadInfoDO douyinUploadInfo;
    private List<String> certainVodUrls;
    private boolean onlyAudio;
    /**
     * 是否录制弹幕
     */
    private boolean recordDamaku;
    private int recordQuality;

    /**
     * 上传到网盘后对文件后缀进行重命名（不含点）
     * 默认为空表示不重命名，例如配置 "zip" 后，xxx.mp4 上传后会被重命名为 xxx.zip
     */
    private String netDiskFileRenameSuffix;


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class BiliUploadInfoDO {
        /**
         * 指定的上传b站cookies
         */
        private String certainBiliCookies;

        /**
         * 版权类型
         * 1是自制，2是转载
         */
        private Integer copyright;

        /**
         * 来源
         */
        private String source;

        /**
         * 分区信息
         */
        private Integer tid;

        /**
         * 上传封面Url
         */
        private String cover;

        /**
         * 开场动画
         */
        private List<String> openingAnimations;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class DouyinUploadInfoDO {
        /**
         * 位置信息
         */
        private String location;
    }
}
