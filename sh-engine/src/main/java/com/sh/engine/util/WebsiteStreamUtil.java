package com.sh.engine.util;


import org.apache.commons.lang3.StringUtils;

/**
 * @Author caiwen
 * @Date 2023 08 27 13 00
 **/
public class WebsiteStreamUtil {
    public static String getHuyaCurStreamUrl(String origin, long timeStamp) {
        String hexTimeStamp = Long.toHexString(timeStamp).toUpperCase();
        String tmp = "clientQueryPcdnSchedule" + hexTimeStamp + "1211huyalive";
        String wsSecret = JavaScriptUtil.execJsByFileName("huya-stream.js", "Ee", tmp);
        String newStreamUrl = origin.replaceAll("(wsSecret=)[^&]+", "$1" + wsSecret)
                .replaceAll("(wsTime=)[^&]+", "$1" + hexTimeStamp);
        return newStreamUrl;
    }
}
