package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 文件上传对象
 */
@Data
public class UploadPartInfo {
    UploadPartInfo() {
    }

    public UploadPartInfo(int partNumber) {
        this.partNumber = partNumber;
    }

    private String etag;

    @JSONField(name = "part_number")
    private int partNumber;

    @JSONField(name = "part_size")
    private int partSize;

    @JSONField(name = "upload_url")
    private String uploadUrl;

    @JSONField(name = "internal_upload_url")
    private String internalUploadUrl;

    @JSONField(name = "content_type")
    private String contentType;

    @JSONField(name = "upload_form_info")
    private String uploadFormInfo;

    @JSONField(name = "internal_upload_form_info")
    private String internalUploadFormInfo;

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public int getPartNumber() {
        return partNumber;
    }

    public void setPartNumber(int partNumber) {
        this.partNumber = partNumber;
    }

    public int getPartSize() {
        return partSize;
    }

    public void setPartSize(int partSize) {
        this.partSize = partSize;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public String getInternalUploadUrl() {
        return internalUploadUrl;
    }

    public void setInternalUploadUrl(String internalUploadUrl) {
        this.internalUploadUrl = internalUploadUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getUploadFormInfo() {
        return uploadFormInfo;
    }

    public void setUploadFormInfo(String uploadFormInfo) {
        this.uploadFormInfo = uploadFormInfo;
    }

    public String getInternalUploadFormInfo() {
        return internalUploadFormInfo;
    }

    public void setInternalUploadFormInfo(String internalUploadFormInfo) {
        this.internalUploadFormInfo = internalUploadFormInfo;
    }
}
