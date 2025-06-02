package com.sh.message.controller;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Preconditions;
import com.sh.config.manager.ConfigFetcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2024 10 08 22 26
 **/
@Slf4j
@Controller
@RequestMapping("/sys")
public class SysController {
    @Resource
    private ConfigFetcher configFetcher;

    @RequestMapping(value = "/getCache", method = {RequestMethod.POST})
    @ResponseBody
    public String getCache(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), "sys-refresh"), "token invalid");
        configFetcher.refresh();
        return "ok";
    }
}
