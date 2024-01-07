package com.sh.engine.model.bili;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * @author caiWen
 * @date 2023/1/27 22:38
 */
@Data
public class BiliWebPreUploadParams {
    @JSONField(name = "OK")
    private Integer ok;

    private String auth;

    @JSONField(name = "chunk_size")
    private Integer chunkSize;

    private String endpoint;

    @JSONField(name = "upos_uri")
    private String uposUri;

    /**
     * 预上传过期时间（分）
     */
    private Integer timeout;

    private String bizId;

    private String uploadUrl;

    private String uploadId;
}
