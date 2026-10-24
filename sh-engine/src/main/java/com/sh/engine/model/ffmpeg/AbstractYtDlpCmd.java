package com.sh.engine.model.ffmpeg;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.CommonParamCache;
import com.sh.config.model.config.CommonParamKey;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;

import java.io.File;

/**
 * @Author : caiwen
 * @Date: 2025/10/4
 */
public abstract class AbstractYtDlpCmd extends AbstractCmd {
    private static final String accountSavePath = EnvUtil.getEnvValue("sh.account-save.path");

    protected AbstractYtDlpCmd( String command ) {
        super(command);
    }

    protected String buildChannelOption(Integer channelType) {
        String options = " ";
        if (channelType == StreamChannelTypeEnum.AFREECA_TV.getType()) {
            CommonParamCache commonParamCache = SpringUtil.getBean(CommonParamCache.class);
            String soopUserName = commonParamCache.get(CommonParamKey.SOOP_USER_NAME);
            String soopPassword = commonParamCache.get(CommonParamKey.SOOP_PASSWORD);
            if (soopUserName != null && soopPassword != null) {
                options = " --username " + soopUserName + " --password " + soopPassword + " ";
            }
        } else if (channelType == StreamChannelTypeEnum.TWITCH.getType()) {
            File cookiesFile = new File(accountSavePath, "twitch-cookies.txt");
            if (cookiesFile.exists()) {
                options = " --cookies " + cookiesFile.getAbsolutePath() + " ";
            }
        } else if (channelType == StreamChannelTypeEnum.YOUTUBE.getType()) {
            File cookiesFile = new File(accountSavePath, "youtube-cookies.txt");
            if (cookiesFile.exists()) {
                options = " --cookies " + cookiesFile.getAbsolutePath() + " ";
            }
        }
        return options;
    }
}
