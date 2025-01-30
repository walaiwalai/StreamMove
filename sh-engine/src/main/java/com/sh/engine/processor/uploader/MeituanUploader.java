package com.sh.engine.processor.uploader;

import com.alibaba.fastjson.TypeReference;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.meta.MeituanWorkMetaData;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 10 06 20 45
 **/
@Slf4j
@Component
public class MeituanUploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Resource
    private MsgSendService msgSendService;
    @Value("${playwright.headless}")
    private boolean headless;

    public static final String AUTH_CODE_KEY = "meituan_login_authcode";
    public static final String IS_SETTING_UP = "meituan_set_up_flag";


    @Override
    public String getType() {
        return UploadPlatformEnum.MEI_TUAN_VIDEO.getType();
    }

    @Override
    public void setUp() {
        if (cacheManager.hasKey(IS_SETTING_UP)) {
            throw new StreamerRecordException(ErrorEnum.UPLOAD_COOKIES_IS_FETCHING);
        }

        cacheManager.localSet(IS_SETTING_UP, 1, 300, TimeUnit.SECONDS);
        try {
            if (!checkAccountValid()) {
                genCookies();
            }
        } finally {
            cacheManager.delete(IS_SETTING_UP);
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File targetFile = new File(recordPath, RecordConstant.LOL_HL_VIDEO);
        if (!targetFile.exists()) {
            // 不存在也当作上传成功
            return true;
        }

        // cookies有效性检测
        setUp();

        // 真正上传
        return doUpload(recordPath);
    }

    private boolean checkAccountValid() {
        File accountFile = getAccoutFile();
        if (!accountFile.exists()) {
            return false;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("https://czz.meituan.com/new/personalHomePage");

            try {
                Locator publishBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布视频"));
                publishBtn.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
                log.info("cookies valid for meituan video");
                context.close();
                browser.close();
                return true;
            } catch (Exception e) {
                log.error("cookies invalid for meituan video");
                context.close();
                browser.close();
                return false;
            }
        }
    }

    private void genCookies() {
        File accountFile = getAccoutFile();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(buildOptions());
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 登录页面
            page.navigate("https://czz.meituan.com/new/login");

            // 输入手机号
            String phoneNumber = ConfigFetcher.getInitConfig().getPhoneNumber();
            page.getByPlaceholder("请输入手机号").fill(phoneNumber);
            page.waitForTimeout(1000);
            page.getByText("发送验证码").click();
            msgSendService.sendText("美团视频号需要进行验证码验证，验证码已发出，请在60s内在微应用回复验证码, 回复格式”#mt_v_code 验证码“");


            int num = 0;
            while (num++ < 20) {
                page.waitForTimeout(3000);
                // 检查缓存中是否有验证码
                String authNumber = cacheManager.get(AUTH_CODE_KEY, new TypeReference<String>() {
                });
                if (authNumber != null) {
                    log.info("receive verify code for {}..., code: {}", getType(), authNumber);
                    page.getByPlaceholder("请输入短信验证码").fill(authNumber);

                    // 点击同意协议
                    page.locator("i").click();
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录 / 注册")).click();
                    page.waitForTimeout(2000);

                    break;
                } else {
                    log.info("waiting for input verify code for {}..., retry: {}/13", getType(), num);
                }
            }

            // 等待用户操作并刷新页面
            page.waitForTimeout(7000);

            Page platformPage = context.newPage();
            platformPage.navigate("https://czz.meituan.com/new/personalHomePage");

            Locator publishBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布视频"));
            publishBtn.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));

            List<Cookie> cookies = context.cookies();
            // cookies中的It就是token
            boolean cookiesValid = CollectionUtils.isNotEmpty(cookies) &&
                    cookies.stream().anyMatch(cookie -> StringUtils.equals("lt", cookie.name));
            if (cookiesValid) {
                // 保存cookie到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
                log.info("gen cookies for {} success", getType());
            }

            // 关闭浏览器
            context.close();
            browser.close();
        } catch (Exception e) {
            log.error("Failed to genCookies for meituan", e);
        }
    }


    private boolean doUpload(String recordPath) {
        File targetFile = new File(recordPath, RecordConstant.LOL_HL_VIDEO);
        String workFilePath = targetFile.getAbsolutePath();
        MeituanWorkMetaData metaData = FileStoreUtil.loadFromFile(
                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
                new TypeReference<MeituanWorkMetaData>() {
                });

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(getAccoutFile().getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("https://czz.meituan.com/new/personalHomePage");
            log.info("begin uploading..., video: {}", workFilePath);

            Locator publishBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布视频"));
            publishBtn.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            publishBtn.click();

            // 上传视频
            uploadVideo(page, workFilePath);

            // 填写标题
            addTitleTags(page, workFilePath, metaData);
            snapshot(page);

            // 检查是否上传视频完成
            detectUploadStatus(page, workFilePath);

            // 保存封面
            fillThumbnail(page, metaData);

            // 发布视频
            publishVideo(page);

            // Save updated cookies
            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(getAccoutFile().getAbsolutePath())));
            log.info("update meituanVideo cookies success, video: {}", workFilePath);
            page.waitForTimeout(2000);

            context.close();
            browser.close();
            return true;
        } catch (Exception e) {
            log.error("meituan fuck", e);
            return false;
        }
    }

    private void uploadVideo(Page page, String workFilePath) {
        page.waitForTimeout(3000);
        page.locator("input.mtd-upload-input[accept*='video']").setInputFiles(Paths.get(workFilePath));
        snapshot(page);
    }

    private void addTitleTags(Page page, String workFilePath, MeituanWorkMetaData metaData) {
        page.locator(".mtd-textarea").click();
        page.keyboard().type(metaData.getTitle());
        for (String tag : metaData.getTags()) {
            page.keyboard().press("Enter");
            page.keyboard().type("#" + tag);
            page.keyboard().press("Space");
        }
        log.info("add tag success, type: {}, path: {}, tags: {}", getType(), workFilePath, metaData.getTags());
    }

    private void detectUploadStatus(Page page, String workFilePath) {
        int errorCnt = 0;
        while (errorCnt < 20) {
            try {
                // 匹配取消视频按钮，代表视频上传完毕
                page.locator(".replace").waitFor(new Locator.WaitForOptions().setTimeout(3000).setState(WaitForSelectorState.VISIBLE));
                log.info("video upload finish for meituan, path: {}", workFilePath);
                break;
            } catch (Exception e) {
                String progress = "-1";
                try {
                    progress = page.locator("span.mtd-progress-text").textContent();
                } catch (Exception ignored) {
                    errorCnt++;
                }
                snapshot(page);
                log.info("video is uploading for meituan, path: {}, progress: {}", workFilePath, progress);
            }
        }

        if (errorCnt >= 20) {
            log.error("video upload failed for wechat, path: {}", workFilePath);
            throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
        }
    }

    /**
     * 保存视频封面
     *
     * @param page
     * @param metaData
     */
    private void fillThumbnail(Page page, MeituanWorkMetaData metaData) {
        page.waitForTimeout(5000);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("设置封面")).click();
        page.waitForTimeout(1000);
        page.getByText("上传封面").click();

        page.locator("input.mtd-upload-input[accept*='image']").setInputFiles(Paths.get(metaData.getPreViewFilePath()));
        page.waitForTimeout(2000);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
    }

    private void publishVideo(Page page) {
        Locator publishButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布").setExact(true));
        publishButton.click();
    }

    private BrowserType.LaunchOptions buildOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"));

//        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
//        if (StringUtils.isNotBlank(httpProxy)) {
//            options.setProxy(httpProxy);
//        }
        return options;
    }
}
