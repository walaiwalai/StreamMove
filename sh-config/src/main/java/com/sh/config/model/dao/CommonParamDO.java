package com.sh.config.model.dao;

import lombok.Data;

import java.util.Date;

/**
 * 通用参数表记录。
 */
@Data
public class CommonParamDO {
    private Long id;
    private Date gmtCreate;
    private Date gmtModified;
    private String key;
    private String value;
    private String env;
}
