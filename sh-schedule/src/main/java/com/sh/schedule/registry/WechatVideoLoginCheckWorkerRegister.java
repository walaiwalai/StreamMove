package com.sh.schedule.registry;

import com.sh.config.manager.ConfigFetcher;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.WechatVideoLoginCheckWorker;
import org.apache.commons.lang3.StringUtils;

/** Registers the periodic WeChat Channels login-state health check. */
public class WechatVideoLoginCheckWorkerRegister extends ProcessWorkerRegister {
    private static final String DEFAULT_CRON = "0 0 0/6 * * ?";

    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return WechatVideoLoginCheckWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        return StringUtils.defaultIfBlank(
                ConfigFetcher.getInitConfig().getWechatVideoLoginCheckCron(), DEFAULT_CRON);
    }

    @Override
    protected String getPrefix() {
        return "WECHAT_VIDEO_LOGIN_CHECK";
    }
}
