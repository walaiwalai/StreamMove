package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 12 12 03
 **/
public class CreateFileResponse {
    @JSONField(name = "domain_id")
    private String domainId;

    @JSONField(name = "part_info_list")
    private List<UploadPartInfo> partInfoList;

    @JSONField(name = "upload_id")
    private String uploadId;

    @JSONField(name = "drive_id")
    private String driveId;

    @JSONField(name = "file_name")
    private String fileName;

    @JSONField(name = "parent_file_id")
    private String parentFileId;

    @JSONField(name = "file_id")
    private String fileId;


    private String location;

    @JSONField(name = "rapid_upload")
    private boolean rapidUpload;

    private String type;

    @JSONField(name = "encrypt_mode")
    private String encryptMode;

    @JSONField(name = "revision_id")
    private String revisionId;

    private boolean exist;

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public boolean isExist() {
        return exist;
    }

    public void setExist(boolean exist) {
        this.exist = exist;
    }

    public List<UploadPartInfo> getPartInfoList() {
        return partInfoList;
    }

    public void setPartInfoList(List<UploadPartInfo> partInfoList) {
        this.partInfoList = partInfoList;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getDriveId() {
        return driveId;
    }

    public void setDriveId(String driveId) {
        this.driveId = driveId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getParentFileId() {
        return parentFileId;
    }

    public void setParentFileId(String parentFileId) {
        this.parentFileId = parentFileId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isRapidUpload() {
        return rapidUpload;
    }

    public void setRapidUpload(boolean rapidUpload) {
        this.rapidUpload = rapidUpload;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEncryptMode() {
        return encryptMode;
    }

    public void setEncryptMode(String encryptMode) {
        this.encryptMode = encryptMode;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }
}
