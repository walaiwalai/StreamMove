package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 11 23 22
 **/
public class CreateFileRequest {
    @JSONField(name = "check_name_mode")
    private String checkNameMode = "auto_rename";

    @JSONField(name = "drive_id")
    private String driverId;

    @JSONField(name = "create_scene")
    private String createScene = "file_upload";

    private String name;

    @JSONField(name = "parent_file_id")
    private String parentFileId;

    @JSONField(name = "pre_hash")
    private String preHash;

    @JSONField(name = "content_hash")
    private String contentHash;

    @JSONField(name = "content_hash_name")
    private String contentHashName;

    @JSONField(name = "proof_code")
    private String proofCode;

    @JSONField(name = "proof_version")
    private String proofVersion;

    @JSONField(name = "part_info_list")
    private List<UploadPartInfo> partInfoList;

    private String size;

    private String type;

    public String getCheckNameMode() {
        return checkNameMode;
    }

    public void setCheckNameMode(String checkNameMode) {
        this.checkNameMode = checkNameMode;
    }

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getContentHashName() {
        return contentHashName;
    }

    public void setContentHashName(String contentHashName) {
        this.contentHashName = contentHashName;
    }

    public String getProofCode() {
        return proofCode;
    }

    public void setProofCode(String proofCode) {
        this.proofCode = proofCode;
    }

    public String getProofVersion() {
        return proofVersion;
    }

    public void setProofVersion(String proofVersion) {
        this.proofVersion = proofVersion;
    }

    public String getCreateScene() {
        return createScene;
    }

    public void setCreateScene(String createScene) {
        this.createScene = createScene;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParentFileId() {
        return parentFileId;
    }

    public void setParentFileId(String parentFileId) {
        this.parentFileId = parentFileId;
    }

    public String getPreHash() {
        return preHash;
    }

    public void setPreHash(String preHash) {
        this.preHash = preHash;
    }

    public List<UploadPartInfo> getPartInfoList() {
        return partInfoList;
    }

    public void setPartInfoList(List<UploadPartInfo> partInfoList) {
        this.partInfoList = partInfoList;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
