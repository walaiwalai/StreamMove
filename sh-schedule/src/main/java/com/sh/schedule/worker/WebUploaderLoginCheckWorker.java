package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.UploaderFactory;
import com.sh.engine.processor.uploader.douyin.DouyinWebRequestSigner;
import com.sh.engine.processor.uploader.meituan.MeituanWebSession;
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

/** Proactively refreshes login state for browser-backed creator uploaders. */
@Slf4j
@DisallowConcurrentExecution
public class WebUploaderLoginCheckWorker extends ProcessWorker {
    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        Environment environment = SpringUtil.getBean(Environment.class);
        String accountSavePath = environment.getProperty("sh.account-save.path");
        if (StringUtils.isBlank(accountSavePath)) {
            throw new IllegalStateException("sh.account-save.path 未配置");
        }
        MsgSendService msgSendService = SpringUtil.getBean(MsgSendService.class);
        checkPlatform(UploadPlatformEnum.DOU_YIN_WEB, accountSavePath, msgSendService);
        checkPlatform(UploadPlatformEnum.WECHAT_VIDEO_WEB, accountSavePath, msgSendService);
        checkPlatform(UploadPlatformEnum.MEI_TUAN_VIDEO, accountSavePath, msgSendService);
    }

    private void checkPlatform(UploadPlatformEnum platform,
                               String accountSavePath,
                               MsgSendService msgSendService) {
        String platformType = platform.getType();
        if (!isPlatformConfigured(platformType)) {
            log.info("No streamer uses {}, skip login-state health check", platformType);
            return;
        }
        Semaphore semaphore = UploaderFactory.getUploaderSemaphore(platformType);
        if (semaphore == null) {
            log.warn("Uploader semaphore is unavailable, skip login-state health check: {}",
                    platformType);
            return;
        }
        if (!semaphore.tryAcquire()) {
            log.info("{} is uploading or checking login state, skip this health check",
                    platformType);
            return;
        }

        File storageStateFile = new File(accountSavePath,
                UploaderFactory.getAccountFileName(platformType));
        try {
            openAndCloseSession(platform, storageStateFile, msgSendService);
            log.info("{} login-state health check succeeded: {}", platformType,
                    storageStateFile.getAbsolutePath());
        } catch (RuntimeException e) {
            String message = platform.getDesc() + "登录态主动检查失败，将在下次检查或上传时重试。\n原因: "
                    + e.getMessage();
            log.error(message, e);
            msgSendService.sendText(message);
        } finally {
            semaphore.release();
        }
    }

    private static void openAndCloseSession(UploadPlatformEnum platform,
                                            File storageStateFile,
                                            MsgSendService msgSendService) {
        switch (platform) {
            case DOU_YIN_WEB:
                try (DouyinWebRequestSigner ignored = new DouyinWebRequestSigner(
                        storageStateFile, msgSendService)) {
                    return;
                }
            case WECHAT_VIDEO_WEB:
                try (WechatVideoWebSession ignored = new WechatVideoWebSession(
                        storageStateFile, msgSendService)) {
                    return;
                }
            case MEI_TUAN_VIDEO:
                try (MeituanWebSession ignored = new MeituanWebSession(
                        storageStateFile, msgSendService)) {
                    return;
                }
            default:
                throw new IllegalArgumentException("不支持登录态检查的平台: " + platform);
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
