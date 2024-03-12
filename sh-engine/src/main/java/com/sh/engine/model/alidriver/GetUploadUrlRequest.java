package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 12 14 03
 **/
public class GetUploadUrlRequest {
    @JSONField(name = "drive_id")
    private String driverId;

    @JSONField(name = "upload_id")
    private String uploadId;


    @JSONField(name = "file_id")
    private String fileId;

    @JSONField(name = "content_md5")
    private String contentMd5;


    @JSONField(name = "part_info_list")
    private List<UploadPartInfo> partInfoList;

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getContentMd5() {
        return contentMd5;
    }

    public void setContentMd5(String contentMd5) {
        this.contentMd5 = contentMd5;
    }

    public List<UploadPartInfo> getPartInfoList() {
        return partInfoList;
    }

    public void setPartInfoList(List<UploadPartInfo> partInfoList) {
        this.partInfoList = partInfoList;
    }
}
