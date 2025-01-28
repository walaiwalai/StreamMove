package com.sh.engine.util;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2025/1/28
 */
@Slf4j
public class UrlUtil {
    public static String getParams(String url, String paramKey) {
        try {
            // 解析 URL
            URI uri = new URI(url);
            // 获取查询字符串
            String query = uri.getQuery();
            if (query == null) {
                return null;
            }
            // 用于存储解析后的键值对
            Map<String, String> params = new HashMap<>();
            // 按 & 分割查询字符串为多个参数项
            String[] paramPairs = query.split("&");
            for (String pair : paramPairs) {
                // 按 = 分割参数项为键和值
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
            // 查找目标参数
            return params.get(paramKey);
        } catch (URISyntaxException e) {
            // 处理 URL 语法错误
            log.error("funk, url: {}, paramKey: {}", url, paramKey);
            return null;
        }
    }

}
