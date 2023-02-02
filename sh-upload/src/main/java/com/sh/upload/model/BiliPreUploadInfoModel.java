package com.sh.upload.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/27 22:38
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BiliPreUploadInfoModel {
    private Integer OK;
    private String auth;
    private Integer chunk_retry;
    private Integer chunk_retry_delay;
    private Integer chunk_size;
    private String endpoint;
    private String upos_uri;

    /**
     * 预上传过期时间（分）
     */
    private Integer timeout;

    private String bizId;
}
