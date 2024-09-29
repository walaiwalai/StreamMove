package com.sh.engine.model.upload;

import com.google.common.collect.ImmutableMap;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.PictureFileUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 45
 **/
@Slf4j
public class DouyinUploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Resource
    private MsgSendService msgSendService;




    private String uploadFilePath;
    private UploadWorkMetaData workMetaData;
    private static final String AUTH_CODE_KEY = "douyin_login_authcode";

    public DouyinUploader(String uploadedDir, String metaDataDir) {
        super(uploadedDir, metaDataDir);
    }

    @Override
    public void setUp() {
        boolean isValid = checkAccountValid();
        if (!isValid) {
            genCookies();
        }
    }

    @Override
    public void doUpload() {
        try (Playwright playwright = Playwright.create()) {
            // 带着cookies创建浏览器
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(getAccoutFile().getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
            log.info("begin uploading..., video: {}", uploadFilePath);

            // 上传视频
            page.setInputFiles(".upload-btn--9eZLd", Paths.get(uploadFilePath));

            // 等待上回完成
            page.waitForURL("https://creator.douyin.com/creator-micro/content/publish?enter_from=publish_page");

            // 填写标题
            log.info("begin filling tags..., video: {}", uploadFilePath);
            String title = workMetaData.getTitle();
            Locator titleInput = page.locator("text=作品标题").locator("..").locator("xpath=following-sibling::div[1]").locator("input");
            if (titleInput.count() > 0) {
                titleInput.fill(title.substring(0, Math.min(title.length(), 30)));
            } else {
                Locator titleContainer = page.locator(".notranslate");
                titleContainer.click();
                page.keyboard().press("Backspace");
                page.keyboard().press("Control+A");
                page.keyboard().press("Delete");
                page.keyboard().type(title);
                page.keyboard().press("Enter");
            }

            // 填写标签
            String cssSelector = ".zone-container";
            List<String> tags = workMetaData.getTags();
            for (int i = 0; i < tags.size(); i++) {
                page.type(cssSelector, "#" + tags.get(i));
                page.press(cssSelector, "Space");
            }

            // Wait for upload to finish
            while (page.locator("div label+div:has-text('重新上传')").count() == 0) {
                log.info("video is uploading, video: {}", uploadFilePath);
                page.waitForTimeout(2000);
                if (page.locator("div.progress-div > div:has-text('上传失败')").count() > 0) {
                    handleUploadError(page);
                }
            }
            log.info("video is upload complete, video: {}", uploadFilePath);

            // Set preview image
            page.locator("text=替换").click();
            page.waitForTimeout(1000);
            page.locator("text=上传封面").click();
            Locator previewUploadDiv = page.locator("div.semi-upload-drag-area");
            previewUploadDiv.click();

            // Upload preview image
            page.setInputFiles("input[type='file']", Paths.get(workMetaData.getPreViewFilePath()));
            page.waitForTimeout(3000);
            page.locator("role=button[name='完成']").click();

            // Set location
            page.locator("div.semi-select span:has-text('输入地理位置')").click();
            page.keyboard().press("Backspace");
            page.keyboard().press("Control+A");
            page.keyboard().press("Delete");
            page.keyboard().type(workMetaData.getLocation());
            page.locator("div[role='listbox'] [role='option']").first().click();

            // Enable third-party platforms (if needed)
            String thirdPartElement = "[class^='info'] > [class^='first-part'] div div.semi-switch";
            if (page.locator(thirdPartElement).count() > 0) {
                String className = page.evalOnSelector(thirdPartElement, "div => div.className").toString();
                if (!className.contains("semi-switch-checked")) {
                    page.locator(thirdPartElement).locator("input.semi-switch-native-control").click();
                }
            }

//            // Set scheduled publishing time (if applicable)
//            if (publishDate != 0) {
//                setScheduleTimeDouyin(page, publishDate); // Placeholder for scheduled publishing
//            }

            // Publish the video
            while (true) {
                Locator publishButton = page.locator("role=button[name='发布']");
                if (publishButton.count() > 0) {
                    publishButton.click();
                }
                try {
                    page.waitForURL("https://creator.douyin.com/creator-micro/content/manage", new Page.WaitForURLOptions().setTimeout(5000));
                    log.info("video is upload success, video: {}", uploadFilePath);
                    break;
                } catch (Exception e) {
                    String currentUrl = page.url();
                    if (currentUrl.contains("https://creator.douyin.com/creator-micro/content/manage")) {
                        log.info("video is upload success, video: {}", uploadFilePath);
                        break;
                    }
                    log.info("video is uploading..., video: {}", uploadFilePath);
                    page.waitForTimeout(500);
                }
            }

            // Save updated cookies
            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(getAccoutFile().getAbsolutePath())));
            log.info("update douyin cookies success, video: {}", uploadFilePath);
            page.waitForTimeout(2000);

            // Close the browser
            context.close();
            browser.close();

        } catch (Exception e) {
            log.error("douyin fuck", e);
        }
    }

    private void handleUploadError(Page page) {
        log.info("upload video error, upload try again, video: {}", uploadFilePath);
        page.locator("div.progress-div [class^='upload-btn-input']").setInputFiles(Paths.get(uploadFilePath));
    }

    public void setScheduleTimeDouyin(Page page, LocalDateTime publishDate) throws InterruptedException {
        Locator labelElement = page.locator("label.radio--4Gpx6:has-text('定时发布')");
        labelElement.click();

        String publishDateHour = publishDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Thread.sleep(1000);
        page.locator(".semi-input[placeholder='日期和时间']").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(publishDateHour);
        page.keyboard().press("Enter");

        Thread.sleep(1000);
    }

    private void genCookies() {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
        File accountFile = new File(accountSavePath, "douyin_accout.json");


        try (Playwright playwright = Playwright.create()) {
            // 启动 Chromium 浏览器，非无头模式
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            // 设置浏览器上下文
            BrowserContext context = browser.newContext();
            // 创建一个新的页面
            Page page = context.newPage();
            // 访问指定的 URL
            page.navigate("https://creator.douyin.com/", new Page.NavigateOptions().setTimeout(20000));

            // 查找二维码元素并获取其 src 属性
            Locator imgElement = page.locator("div.account-qrcode-QvXsyd div.qrcode-image-QrGzx7 img:first-child");
            imgElement.waitFor(new Locator.WaitForOptions().setTimeout(10000));
            String imgElementSrc = imgElement.getAttribute("src");

            // 保存二维码图片
            File qrCodeFile = PictureFileUtil.saveBase64Image(imgElementSrc);

            int num = 1;
            while (true) {
                // 等待 3 秒
                Thread.sleep(3000);

                // 检查是否已登录
                if (page.url().contains("creator.douyin.com/creator-micro/home")) {
                    break;
                }

                // 检查是否需要身份验证
                Locator authDiv = page.locator("text=身份验证");
                if (authDiv.isVisible()) {
                    // 需要短信验证码验证
                    page.locator("text=接收短信验证").click();
                    Thread.sleep(1000);
                    page.locator("text=获取验证码").click();
                    msgSendService.sendText("需要进行验证码验证，验证码已发出，请在60s内在微应用回复验证码");

                    int numTwo = 1;
                    while (true) {
                        Thread.sleep(3000);
                        // 检查缓存中是否有验证码
                        String authNumber = (String) cacheManager.get(AUTH_CODE_KEY);
                        if (authNumber != null) {
                            page.locator("input[placeholder='请输入验证码']").nth(1).fill(authNumber);
                            page.locator("text=验证").filter(new Locator.FilterOptions().setHasText("验证")).click();
                            Thread.sleep(2000);

                            cacheManager.delete("douyin_login_need_auth");
                            break;
                        }
                        if (numTwo > 20) {
                            break;
                        }
                        numTwo++;
                    }
                }
                if (num > 13) {
                    break;
                }
                num++;
            }

            // 获取 cookie
            List<Cookie> cookies = context.cookies();
            Map<String, String> userInfo = null;

            // 如果 cookie 数量超过 30，则表示登录成功
            if (cookies.size() > 30) {
                // 获取用户信息
                String thirdIdCont = page.locator("text=抖音号：").innerText();
                String thirdId = thirdIdCont.split("：")[1];
                userInfo = ImmutableMap.of(
                        "account_id", thirdId, // 抖音号
                        "username", page.locator("div.rNsML").innerText(), // 用户名
                        "avatar", page.locator("div.t4cTN img").first().getAttribute("src") // 头像
                );
                // 保存 cookie 到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
            }

            // 关闭浏览器
            context.close();
            browser.close();
        } catch (Exception e) {
            log.error("Failed to genCookies, path: {}", accountFile.getAbsolutePath(), e);
        }
    }

    private boolean checkAccountValid() {
        File accountFile = getAccoutFile();
        if (!accountFile.exists()) {
            return false;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath()));
            BrowserContext context = browser.newContext(contextOptions);

            Page page = context.newPage();
            page.navigate("https://creator.douyin.com/creator-micro/content/upload");

            try {
                // 等待指定的选择器 5 秒钟
                page.waitForSelector("div:text('我是创作者')", new Page.WaitForSelectorOptions().setTimeout(5000));
                log.info("cookies invalid for douyin");
                // Cookie 失效，删除 JSON 文件
                accountFile.deleteOnExit();
                context.close();
                browser.close();
                return false;
            } catch (PlaywrightException e) {
                // Cookie 有效
                log.info("cookies valid for douyin");
                context.close();
                browser.close();
                return true;
            }
        }
    }

    private File getAccoutFile() {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
        return new File(accountSavePath, "douyin_accout.json");
    }
}
