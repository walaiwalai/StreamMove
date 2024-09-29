package com.sh.message.model.wecom;

import lombok.Data;

/**
 * @Author caiwen
 * @Date 2023 08 20 09 48
 **/
@Data
public class WxAccessTokenRespModel {
    private int errcode;
    private String errmsg;

    private String access_token;

    private Integer axpires_in;
}
