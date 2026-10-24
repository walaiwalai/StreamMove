package com.sh.config.controller;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Preconditions;
import com.sh.config.manager.CommonParamCache;
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
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * @Author caiwen
 * @Date 2024 10 08 22 26
 **/
@Slf4j
@Controller
@RequestMapping("/sys")
public class SysController {
    private static final String REFRESH_TOKEN = "sys-refresh";
    public static final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    @Resource
    private ConfigFetcher configFetcher;
    @Resource
    private LocalCacheManager localCacheManager;
    @Resource
    private StatusManager statusManager;
    @Resource
    private CommonParamCache commonParamCache;

    @RequestMapping(value = "/refresh", method = {RequestMethod.POST})
    @ResponseBody
    public String getCache(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), REFRESH_TOKEN), "token invalid");
        configFetcher.refresh();
        localCacheManager.clearAll();

        // gc一下，减少内存
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        long start = System.currentTimeMillis();
        log.info("before heap: {}M, nonHeap: {}", heapMemoryUsage.getUsed() / 1024 / 1024, nonHeapMemoryUsage.getUsed() / 1024 / 1024);
        System.gc();
        heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        log.info("after heap: {}M, nonHeap: {}M, cost: {}ms", heapMemoryUsage.getUsed() / 1024 / 1024, nonHeapMemoryUsage.getUsed() / 1024 / 1024, System.currentTimeMillis() - start);

        return "ok";
    }

    @RequestMapping(value = "/statusInfo", method = {RequestMethod.POST})
    @ResponseBody
    public String getStatus(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), REFRESH_TOKEN), "token invalid");
        return statusManager.printInfo();
    }

    @RequestMapping(value = "/common-param/refresh", method = RequestMethod.POST)
    @ResponseBody
    public String refreshCommonParam(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), REFRESH_TOKEN), "token invalid");
        commonParamCache.refresh();
        return "ok";
    }

    /** 使用现有刷新令牌，强制刷新数据库中所有环境的通用参数缓存。 */
    @RequestMapping(value = "/common-param/refresh-all", method = RequestMethod.POST)
    @ResponseBody
    public String refreshAllCommonParams(@RequestBody JSONObject requestBody) {
        Preconditions.checkArgument(StringUtils.equals(requestBody.getString("token"), REFRESH_TOKEN), "token invalid");
        commonParamCache.refreshAll();
        return "ok";
    }
}
