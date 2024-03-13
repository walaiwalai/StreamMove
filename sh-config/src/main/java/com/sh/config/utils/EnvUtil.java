package com.sh.config.utils;

import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @Author caiwen
 * @Date 2024 02 02 21 27
 **/
@Slf4j
public class EnvUtil {
    public static boolean isProd() {
        if (SpringUtil.getApplicationContext() == null) {
            log.info("applicationContext not inited !!!");
            return false;
        }
        String activeProfile = SpringUtil.getActiveProfile();
        return activeProfile != null && StringUtils.equals(activeProfile, "prod");
    }
}
