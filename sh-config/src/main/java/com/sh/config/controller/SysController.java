package com.sh.config.controller;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Preconditions;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.LocalCacheManager;
import com.sh.config.manager.StatusManager;
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
    @Resource
    private LocalCacheManager localCacheManager;
    @Resource
    private StatusManager statusManager;

    @RequestMapping(value = "/refresh", method = {RequestMethod.POST})
    @ResponseBody
    public String getCache(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), "sys-refresh"), "token invalid");
        configFetcher.refresh();
        localCacheManager.clearAll();
        return "ok";
    }

    @RequestMapping(value = "/statusInfo", method = {RequestMethod.POST})
    @ResponseBody
    public String getStatus(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), "sys-refresh"), "token invalid");
        String info = statusManager.printInfo();
        return info;
    }
}
