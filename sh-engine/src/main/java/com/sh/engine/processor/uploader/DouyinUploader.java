package com.sh.engine.processor.uploader;

import com.alibaba.fastjson.TypeReference;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.LocalCacheManager;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 45
 **/
@Slf4j
@Component
public class DouyinUploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Resource
    private LocalCacheManager localCacheManager;
    @Resource
    private MsgSendService msgSendService;

    @Value("${playwright.headless}")
    private boolean headless;
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    public static final String AUTH_CODE_KEY = "douyin_login_authcode";
    private static final String IS_SETTING_UP = "douyin_set_up_flag";

    @Override
    public String getType() {
        return UploadPlatformEnum.DOU_YIN.getType();
    }

    @Override
    public void setUp() {
        if (localCacheManager.hasKey(IS_SETTING_UP)) {
            throw new StreamerRecordException(ErrorEnum.UPLOAD_COOKIES_IS_FETCHING);
        }
        localCacheManager.set(IS_SETTING_UP, 1, 300, TimeUnit.SECONDS);

        try {
            if (!checkAccountValid()) {
                genCookies();
            }
        } finally {
            localCacheManager.delete(IS_SETTING_UP);
        }
    }

    @Override
    public boolean upload(String recordPath) {
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

    private boolean doUpload(String recordPath) {
        File targetFile = new File(recordPath, RecordConstant.LOL_HL_VIDEO);
        String workFilePath = targetFile.getAbsolutePath();

        // 加载元数据
        DouyinWorkMetaData metaData = FileStoreUtil.loadFromFile(
                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
                new TypeReference<DouyinWorkMetaData>() {
                });

        // 开始上传
        try (Playwright playwright = Playwright.create()) {
            // 带着cookies创建浏览器
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"))
            );
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(getAccoutFile().getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
            log.info("begin uploading..., video: {}", workFilePath);

            // 上传视频
            page.setInputFiles("div[class^='container'] input", Paths.get(workFilePath));

            // 等待上回完成
//            page.waitForURL("https://creator.douyin.com/creator-micro/content/publish?enter_from=publish_page");

            // 填写标题
            fillTitle(page, metaData);

            // 填写标签
            fillTags(page, metaData);

            // 等待视频上传完成
            waitingVideoUploadFinish(page, workFilePath);

            // 保存封面
//            if (StringUtils.isNotBlank(metaData.getPreViewFilePath()) && new File(metaData.getPreViewFilePath()).exists()) {
//                try {
//                    fillThumbnail(page, metaData);
//                    log.info("upload thumbnail success, path: {}, thumbnail: {}", workFilePath, metaData.getPreViewFilePath());
//                } catch (Exception e) {
//                    log.error("upload thumbnail failed, will skip, path: {}, thumbnail: {}", workFilePath, metaData.getPreViewFilePath());
//                }
//            }

            // 填写位置
            if (StringUtils.isNotBlank(metaData.getLocation())) {
                try {
                    fillLocation(page, metaData);
                    log.info("upload thumbnail success, path: {}, location: {}", workFilePath, metaData.getLocation());
                } catch (Exception e) {
                    log.error("upload thumbnail failed, will skip, path: {}, location: {}", workFilePath, metaData.getLocation());
                }
            }

            // 三方平台勾选
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

            // 发布视频
            publishVideo(page, workFilePath);

            // Save updated cookies
            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(getAccoutFile().getAbsolutePath())));
            log.info("update douyin cookies success, video: {}", workFilePath);
            page.waitForTimeout(2000);

            // Close the browser
            context.close();
            browser.close();
            return true;
        } catch (Exception e) {
            log.error("douyin fuck", e);
            return false;
        }
    }

    private void handleUploadError(Page page, String workFilePath) {
        log.info("upload video error, upload try again, video: {}", workFilePath);
        page.locator("div.progress-div [class^='upload-btn-input']").setInputFiles(Paths.get(workFilePath));
    }

    public void setScheduleTimeDouyin(Page page, LocalDateTime publishDate) throws InterruptedException {
        Locator labelElement = page.locator("label.radio--4Gpx6:has-text('定时发布')");
        labelElement.click();

        String publishDateHour = publishDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        page.waitForTimeout(1000);
        page.locator(".semi-input[placeholder='日期和时间']").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(publishDateHour);
        page.keyboard().press("Enter");

        page.waitForTimeout(1000);
    }

    /**
     * 填充视频标签
     *
     * @param page
     * @param metaData
     */
    private void fillTitle(Page page, DouyinWorkMetaData metaData) {
        String title = metaData.getTitle();
        Locator titleInput = page.locator("text=作品标题").locator("..")
                .locator("xpath=following-sibling::div[1]")
                .locator("input");
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
    }

    /**
     * 填充标签
     *
     * @param page
     * @param metaData
     */
    private void fillTags(Page page, DouyinWorkMetaData metaData) {
        String cssSelector = ".zone-container";
        List<String> tags = metaData.getTags();
        for (int i = 0; i < tags.size(); i++) {
            page.type(cssSelector, "#" + tags.get(i));
            page.press(cssSelector, "Space");
        }
    }

    /**
     * 等待视频上传完成
     *
     * @param page
     * @param workFilePath
     */
    private void waitingVideoUploadFinish(Page page, String workFilePath) {
        while (page.locator("text=重新上传").count() == 0) {
            log.info("video is uploading, video: {}", workFilePath);
            page.waitForTimeout(2000);
            if (page.locator("text=上传失败").count() > 0) {
                handleUploadError(page, workFilePath);
            }
        }
        log.info("video is upload complete, video: {}", workFilePath);
    }

    /**
     * 上传地址位置
     *
     * @param page
     * @param metaData
     */
    private void fillLocation(Page page, DouyinWorkMetaData metaData) {
        page.waitForTimeout(5000);

        page.locator("div.semi-select span:has-text('输入地理位置')").click();
        page.keyboard().press("Backspace");
        page.keyboard().press("Control+A");
        page.keyboard().press("Delete");
        page.keyboard().type(metaData.getLocation());

        page.waitForTimeout(3000);
        page.locator("div[role='listbox'] [role='option']").first().click();
    }

    /**
     * 保存视频封面
     *
     * @param page
     * @param metaData
     */
    private void fillThumbnail(Page page, DouyinWorkMetaData metaData) {
        page.waitForTimeout(5000);
        page.click("text='选择封面'");
        page.waitForTimeout(1000);
        page.click("text='上传封面'");

        // 定位到上传区域并点击
        page.locator("div[class^='semi-upload upload'] >> input.semi-upload-hidden-input").setInputFiles(Paths.get(metaData.getPreViewFilePath()));
        page.waitForTimeout(2000);
        page.locator("div[class^='uploadCrop'] button:has-text('完成')").click();
    }

    private void publishVideo(Page page, String workFilePath) {
        while (true) {
            Locator publishButton = page.locator("role=button[name='发布']");
            if (publishButton.count() > 0) {
                publishButton.click();
            }
            try {
                page.waitForURL("https://creator.douyin.com/creator-micro/content/manage", new Page.WaitForURLOptions().setTimeout(5000));
                log.info("video is upload success, video: {}", workFilePath);
                break;
            } catch (Exception e) {
                String currentUrl = page.url();
                if (currentUrl.contains("https://creator.douyin.com/creator-micro/content/manage")) {
                    log.info("video is upload success, video: {}", workFilePath);
                    break;
                }
                log.info("video is uploading..., video: {}", workFilePath);
                page.waitForTimeout(500);
            }
        }
    }


    /**
     * 生成cookies
     */
    private void genCookies() {
        File accountFile = getAccoutFile();

        try (Playwright playwright = Playwright.create()) {
            // 启动 Chromium 浏览器，非无头模式
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"))
            );
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
            File qrCodeFile = new File(accountSavePath, UploaderFactory.getQrCodeFileName(getType()));
            PictureFileUtil.saveBase64Image(imgElementSrc, qrCodeFile);
            msgSendService.sendText("需要扫码验证，扫描下方二维码");
            msgSendService.sendImage(qrCodeFile);

            int num = 1;
            while (true) {
                // 等待 3 秒
                page.waitForTimeout(3000);

                // 检查是否已登录
                if (page.url().contains("creator.douyin.com/creator-micro/home")) {
                    break;
                }

                // 检查是否需要身份验证
                Locator authDiv = page.locator("text=身份验证");
                if (authDiv.isVisible()) {
                    // 需要短信验证码验证
                    page.locator("text=接收短信验证").click();
                    page.waitForTimeout(1000);
                    page.locator("text=获取验证码").click();
                    msgSendService.sendText("微信视频号需要进行验证码验证，验证码已发出，请在60s内在微应用回复验证码");

                    int numTwo = 1;
                    while (true) {
                        page.waitForTimeout(3000);
                        // 检查缓存中是否有验证码
                        String authNumber = cacheManager.get(AUTH_CODE_KEY, new TypeReference<String>() {
                        });
                        if (authNumber != null) {
                            page.locator("input[placeholder='请输入验证码']").nth(1).fill(authNumber);
                            page.getByText("验证", new Page.GetByTextOptions().setExact(true)).filter().click();
                            page.waitForTimeout(2000);

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
//                userInfo = ImmutableMap.of(
//                        "account_id", thirdId, // 抖音号
//                        "username", page.locator("div.rNsML").innerText(), // 用户名
//                        "avatar", page.locator("div.t4cTN img").first().getAttribute("src") // 头像
//                );
                // 保存 cookie 到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
                log.info("gen cookies for {} success", getType());
            }

            // 关闭浏览器
            context.close();
            browser.close();
        } catch (Exception e) {
            log.error("Failed to genCookies for douyin, path: {}", accountFile.getAbsolutePath(), e);
        }
    }

    /**
     * 账号是否生效
     *
     * @return true/false
     */
    private boolean checkAccountValid() {
        File accountFile = getAccoutFile();
        if (!accountFile.exists()) {
            return false;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"))
            );
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
}
