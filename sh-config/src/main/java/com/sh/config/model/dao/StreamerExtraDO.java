package com.sh.config.model.dao;

import com.google.common.collect.Lists;
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


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class BiliUploadInfoDO {
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
