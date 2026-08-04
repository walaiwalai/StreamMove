package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.UploaderFactory;
import com.sh.engine.processor.uploader.wechat.WechatVideoWebSession;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.File;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Proactively refreshes the WeChat Channels creator login state on the upload machine. */
@Slf4j
@DisallowConcurrentExecution
public class WechatVideoLoginCheckWorker extends ProcessWorker {
    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        String platform = UploadPlatformEnum.WECHAT_VIDEO_WEB.getType();
        if (!isPlatformConfigured(platform)) {
            log.info("No streamer uses {}, skip login-state health check", platform);
            return;
        }

        Semaphore semaphore = UploaderFactory.getUploaderSemaphore(platform);
        if (semaphore == null) {
            log.warn("Uploader semaphore is unavailable, skip login-state health check: {}",
                    platform);
            return;
        }
        if (!semaphore.tryAcquire()) {
            log.info("{} is uploading or checking login state, skip this health check", platform);
            return;
        }

        MsgSendService msgSendService = SpringUtil.getBean(MsgSendService.class);
        try {
            Environment environment = SpringUtil.getBean(Environment.class);
            String accountSavePath = environment.getProperty("sh.account-save.path");
            if (StringUtils.isBlank(accountSavePath)) {
                throw new IllegalStateException("sh.account-save.path 未配置");
            }
            File storageStateFile = new File(accountSavePath,
                    UploaderFactory.getAccountFileName(platform));
            try (WechatVideoWebSession ignored = new WechatVideoWebSession(
                    storageStateFile, msgSendService)) {
                log.info("WeChat Channels login-state health check succeeded: {}",
                        storageStateFile.getAbsolutePath());
            }
        } catch (RuntimeException e) {
            msgSendService.sendText("微信视频号登录态主动检查失败，将在下次检查或上传时重试。\n原因: "
                    + e.getMessage());
            throw e;
        } finally {
            semaphore.release();
        }
    }

    static boolean isPlatformConfigured(String platform) {
        List<StreamerConfig> streamerConfigs = ConfigFetcher.getStreamerInfoList();
        if (streamerConfigs == null || streamerConfigs.isEmpty()) {
            return false;
        }
        for (StreamerConfig streamerConfig : streamerConfigs) {
            if (streamerConfig != null && streamerConfig.getUploadPlatforms() != null
                    && streamerConfig.getUploadPlatforms().contains(platform)) {
                return true;
            }
        }
        return false;
    }
}
