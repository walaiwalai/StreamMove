package com.sh.engine.model.alidriver;

import lombok.Data;

/**
 * 文件上传对象
 */
@Data
public class AliUploadPartInfo {
    private String internalUploadUrl;
    private String uploadUrl;
    private int partNumber;
}
