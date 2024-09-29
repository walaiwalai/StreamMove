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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Base64;
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
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
        File accountFile = new File(accountSavePath, "douyin_accout.json");
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
}
