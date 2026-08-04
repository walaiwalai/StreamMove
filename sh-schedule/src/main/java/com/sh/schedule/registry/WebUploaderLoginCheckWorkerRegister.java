package com.sh.schedule.registry;

import com.sh.config.manager.ConfigFetcher;
import com.sh.schedule.worker.ProcessWorker;
import com.sh.schedule.worker.WebUploaderLoginCheckWorker;
import org.apache.commons.lang3.StringUtils;

/** Registers the periodic login-state health check for browser-backed creator uploaders. */
public class WebUploaderLoginCheckWorkerRegister extends ProcessWorkerRegister {
    private static final String DEFAULT_CRON = "0 0 0/6 * * ?";

    @Override
    public Class<? extends ProcessWorker> getWorker() {
        return WebUploaderLoginCheckWorker.class;
    }

    @Override
    protected boolean needRegistry() {
        return true;
    }

    @Override
    public String getCronExpr() {
        String configured = ConfigFetcher.getInitConfig().getWebUploaderLoginCheckCron();
        if (StringUtils.isBlank(configured)) {
            configured = ConfigFetcher.getInitConfig().getWechatVideoLoginCheckCron();
        }
        return StringUtils.defaultIfBlank(configured, DEFAULT_CRON);
    }

    @Override
    protected String getPrefix() {
        return "WEB_UPLOADER_LOGIN_CHECK";
    }
}
