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

    public static String getEnvValue(String key) {
        if (SpringUtil.getApplicationContext() == null) {
            log.info("applicationContext not inited !!!");
            return null;
        }
        return SpringUtil.getApplicationContext().getEnvironment()
                .getProperty(key, String.class);
    }

    public static Integer getEnvInt(String key) {
        if (SpringUtil.getApplicationContext() == null) {
            log.info("applicationContext not inited !!!");
            return null;
        }
        return SpringUtil.getApplicationContext().getEnvironment()
                .getProperty(key, Integer.class);
    }

    public static Boolean getEnvBoolean(String key) {
        if (SpringUtil.getApplicationContext() == null) {
            log.info("applicationContext not inited !!!");
            return null;
        }
        return SpringUtil.getApplicationContext().getEnvironment()
                .getProperty(key, Boolean.class);
    }
}
