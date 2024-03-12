package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.annotation.JSONField;


/**
 * 阿里云盘文件对象
 */
public class AliFileDTO {
    private String name;

    @JSONField(name = "file_id")
    private String fileId;

    private String url;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
