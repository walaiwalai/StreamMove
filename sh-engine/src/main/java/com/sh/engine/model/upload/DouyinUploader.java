package com.sh.engine.model.upload;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.model.record.Recorder;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 45
 **/
@Slf4j
public class DouyinUploader extends Uploader {
    public DouyinUploader(String uploadedDir, String metaDataDir) {
        super(uploadedDir, metaDataDir);
    }

    @Override
    public void setUp() {

    }

    @Override
    public void doUpload() {

    }

    private void genCookies() {
        try (Playwright playwright = Playwright.create()) {
            // 启动 Chromium 浏览器，非无头模式
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            // 设置浏览器上下文
            BrowserContext context = browser.newContext();
            // 创建一个新的页面
            Page page = context.newPage();

            // 访问指定的 URL
            page.navigate("https://creator.douyin.com/", new Page.NavigateOptions().setTimeout(20000));

            // 查找二维码元素并获取其 src 属性并保存
            Locator imgElement = page.locator("div.account-qrcode-QvXsyd div.qrcode-image-QrGzx7 img:first-child");
            imgElement.waitFor(new Locator.WaitForOptions().setTimeout(10000));
            String imgElementSrc = imgElement.getAttribute("src");
            saveBase64Image(imgElementSrc);

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

                    int numTwo = 1;
                    while (true) {
                        Thread.sleep(3000);
                        // 检查缓存中是否有验证码
                        String authNumber = cacheGetData("douyin_login_authcode_" + queueId);
                        if (authNumber != null) {
                            page.locator("input[placeholder='请输入验证码']").nth(1).fill(authNumber);
                            page.locator("text=验证", new Locator.ClickOptions().setExact(true)).click();
                            Thread.sleep(2000);
                            cacheDelete("douyin_login_need_auth_" + queueId);
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
                userInfo = Map.of(
                        "account_id", thirdId, // 抖音号
                        "username", page.locator("div.rNsML").innerText(), // 用户名
                        "avatar", page.locator("div.t4cTN img").first().getAttribute("src") // 头像
                );
                String accountFile = accountFilePath + "/" + accountId + "_" + thirdId + "_account.json";
                // 保存 cookie 到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile)));
                // 缓存当前登录状态
                cacheData("douyin_login_status_" + accountId, 1, 60); // 60 秒
                cacheData("douyin_login_status_third_" + accountId + "_" + thirdId, 1, 604800); // 一周
            }

            // 关闭浏览器
            context.close();
            browser.close();
            return userInfo;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 保存 base64 字符串为本地图片文件
    public static void saveBase64Image(String base64Image) {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();

        try {
            // 去掉 Base64 字符串的前缀（如果有，如 "data:image/png;base64,"）
            if (base64Image.startsWith("data:image")) {
                base64Image = base64Image.substring(base64Image.indexOf(",") + 1);
            }

            // 将 Base64 字符串解码为字节数组
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);

            // 将字节数组转换为输入流
            InputStream inputStream = new ByteArrayInputStream(imageBytes);

            // 读取输入流为 BufferedImage
            BufferedImage bufferedImage = ImageIO.read(inputStream);

            // 创建输出文件
            File outputFile = new File(accountSavePath, "douyin_login_qrcode.png");

            // 将 BufferedImage 保存为本地文件
            ImageIO.write(bufferedImage, "png", outputFile);
            log.info("Image saved successfully to: {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save image: {}", accountSavePath, e);
            System.err.println("Failed to save image: " + e.getMessage());
        }
    }



    private boolean checkAccountValid() {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
        File accountFile = new File(accountSavePath, "douyin_accout.json");
        if (!accountFile.exists()) {
            return false;
        }

        // 使用 Playwright
        try (Playwright playwright = Playwright.create()) {
            // 启动无头浏览器
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            // 从已有的 cookie 文件中加载浏览器上下文
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath()));
            BrowserContext context = browser.newContext(contextOptions);

            // 创建一个新的页面
            Page page = context.newPage();
            // 访问指定的 URL
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
