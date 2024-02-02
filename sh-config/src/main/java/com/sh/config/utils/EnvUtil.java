package com.sh.config.utils;

import cn.hutool.extra.spring.SpringUtil;
import org.apache.commons.lang3.StringUtils;

/**
 * @Author caiwen
 * @Date 2024 02 02 21 27
 **/
public class EnvUtil {
    public static boolean isProd() {
        String activeProfile = SpringUtil.getActiveProfile();
        return activeProfile != null && StringUtils.equals(activeProfile, "prod");
    }
}
