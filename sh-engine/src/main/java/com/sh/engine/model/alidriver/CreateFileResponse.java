package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 12 12 03
 **/
@Data
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
}
