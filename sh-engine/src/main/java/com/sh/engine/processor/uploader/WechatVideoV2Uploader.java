package com.sh.engine.processor.uploader;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 腾讯视频号上传
 * 三方工具：http://loong.videostui.com
 *
 * @Author : caiwen
 * @Date: 2024/10/2
 */
@Slf4j
@Component
public class WechatVideoV2Uploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Value("${playwright.headless}")
    private boolean headless;

    private static final String IS_SETTING_UP = "wechat_set_up_flag";

    @Override
    public String getType() {
        return UploadPlatformEnum.WECHAT_VIDEO_V2.getType();
    }

    @Override
    public void setUp() {
        if (cacheManager.hasKey(IS_SETTING_UP)) {
            throw new StreamerRecordException(ErrorEnum.UPLOAD_COOKIES_IS_FETCHING);
        }

        cacheManager.set(IS_SETTING_UP, 1, 300, TimeUnit.SECONDS);
        try {
            if (!checkAccountValid()) {
                genCookies();
            }
        } finally {
            cacheManager.delete(IS_SETTING_UP);
        }

    }

    private void genCookies() {
        File accountFile = getAccoutFile();
        String phoneNumber = ConfigFetcher.getInitConfig().getPhoneNumber();
        String password = ConfigFetcher.getInitConfig().getPassword();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(buildOptions());

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 加载首页
            page.navigate("http://loong.videostui.com/#/login");
            page.getByPlaceholder("请输入手机号登录").click();
            page.getByPlaceholder("请输入手机号登录").fill(phoneNumber);
            page.getByPlaceholder("请输入密码").click();
            page.getByPlaceholder("请输入密码").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登 录")).click();

            // 等待用户操作并刷新页面
            page.waitForTimeout(7000);

            // 打开平台页面获取用户信息
            Page platformPage = context.newPage();
            platformPage.navigate("http://loong.videostui.com/#/publish");
            platformPage.waitForURL("http://loong.videostui.com/#/publish");

            List<Cookie> cookies = context.cookies();
            Map<String, String> userInfo = null;
            if (CollectionUtils.isNotEmpty(cookies)) {
                // 保存cookie到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
                log.info("gen cookies for {} success", getType());
            }

            // 关闭浏览器
            context.close();
            browser.close();
        } catch (Exception e) {
            log.error("Failed to genCookies for tencent", e);
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File targetFile = new File(recordPath, "highlight.mp4");
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
            Browser browser = playwright.firefox().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("http://loong.videostui.com/#/publish");
            page.waitForTimeout(5000);
            boolean isValid = page.getByText("发布设置").count() > 0;

            context.close();
            browser.close();

            if (isValid) {
                log.info("cookies valid for wechat video");
            } else {
                log.info("cookies invalid for wechat video");
            }

            return isValid;
        } catch (Exception e) {
            log.error("wechat video fuck", e);
            return false;
        }
    }

    private boolean doUpload(String recordPath) {
        File targetFile = new File(recordPath, "highlight.mp4");
        String workFilePath = targetFile.getAbsolutePath();
        String cookiesPath = getAccoutFile().getAbsolutePath();
        WechatVideoMetaData metaData = FileStoreUtil.loadFromFile(
                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
                new TypeReference<WechatVideoMetaData>() {
                });

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(cookiesPath)));

            Page page = context.newPage();
            page.navigate("http://loong.videostui.com/#/publish");
            log.info("Video begin uploading, type: {}, path: {}", getType(), workFilePath);

            page.waitForURL("http://loong.videostui.com/#/publish");

            // 上传文件
            uploadVideo(page, workFilePath);

            // 添加短标题
            addShortTitle(page, metaData.getTitle());

            // 添加封面
            addThumbnail(page, metaData.getPreViewFilePath());

            // 添加视频标签
            addTitleTags(page, workFilePath, metaData);

            // 检查是否上传视频完成
            detectUploadStatus(page, workFilePath);

            // 选择账号
            chooseAccount(page);

            // 发布视频
            publishVideo(page, workFilePath, cookiesPath, metaData);

            // Save updated cookies
            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(cookiesPath)));
            log.info("update wechatVideo cookies success, video: {}", workFilePath);
            page.waitForTimeout(2000);

            context.close();
            browser.close();

            return true;
        } catch (Exception e) {
            log.error("wechat video fuck", e);
            return false;
        }
    }

    private void uploadVideo(Page page, String workFilePath) {
        page.waitForTimeout(2000);
        page.locator(".ant-upload.ant-upload-select.ant-upload-select-text input[type='file']").setInputFiles(Paths.get(workFilePath));
    }

    private void addTitleTags(Page page, String workFilePath, WechatVideoMetaData metaData) {
        for (String tag : metaData.getTags()) {
            page.getByText("+ 添加话题标签").click();
            page.keyboard().type("#" + tag);
            page.keyboard().press("Enter");
        }
        log.info("add tag success, type: {}, path: {}, tags: {}", getType(), workFilePath, metaData.getTags());
    }

    private void detectUploadStatus(Page page, String workFilePath) {
        int errorCnt = 0;
        while (errorCnt < 10) {
            try {
                // 等待上传完成小图标
                Locator finishCircle = page.locator(".anticon.anticon-check-circle");
                if (finishCircle.count() > 0) {
                    log.info("video upload finish for wechat, path: {}", workFilePath);
                    page.waitForTimeout(2000);
                    break;
                } else {
                    String progress = page.locator("span.ant-progress-text").textContent();
                    log.info("video is uploading for wechat, path: {}, progress: {}", workFilePath, progress);
                    page.waitForTimeout(2000);
                }
            } catch (Exception e) {
                log.info("video is uploading for wechat, path: {}", workFilePath, e);
                errorCnt++;
                page.waitForTimeout(2000);
            }
        }
    }

    private void chooseAccount(Page page) {
        page.getByText("+ 添加帐号").click();
        page.waitForTimeout(2000);

        page.locator(".ant-checkbox-input").nth(1).click();
        page.getByText("确 定").click();
    }


    private void addShortTitle(Page page, String title) {
        Locator titleLoc = page.getByPlaceholder("请输入视频标题");
        titleLoc.click();
        titleLoc.fill(title);
    }

    private void addThumbnail(Page page, String previewPath) {
        page.waitForTimeout(2000);
        page.locator(".ant-upload.ant-upload-select.ant-upload-select-picture-card input[type='file']").setInputFiles(Paths.get(previewPath));
    }

    private void publishVideo(Page page, String workFilePath, String cookiesPath, WechatVideoMetaData metaData) {
        // 获取cookies
        JSONObject cookieObj = FileStoreUtil.loadFromFile(new File(cookiesPath), new TypeReference<JSONObject>() {
        });
        Map<String, String> kvMap = cookieObj.getJSONArray("cookies").stream()
                .collect(Collectors.toMap(obj -> ((JSONObject) obj).getString("name"), obj -> ((JSONObject) obj).getString("value")));
        String authorization = kvMap.get("Authorization").replace("%20", " ");

        // 拦截请求获取参数
        final JSONObject[] createParam = {new JSONObject()};
        page.route("http://loong.videostui.com/prod-api/manage/production/create", route -> {
            createParam[0] = extractCreateVideoParam(route);
            route.abort();
        });
        page.route("http://loong.videostui.com/prod-api/manage/production/publish", Route::abort);
        page.getByText("一键发布").click();

        // 等待拦截发生
        page.waitForTimeout(5000);

        // api请求
        String videoUrl = createParam[0].getString("videoUrl");
        String imgUrl = createParam[0].getString("imgUrl");
        String workId = createWork(authorization, videoUrl, imgUrl, workFilePath, metaData);
        if (StringUtils.isBlank(workId)) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }
        boolean publishSuccess = publishWork(authorization, workId);
        if (!publishSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        log.info("video upload success, path: {}", workFilePath);
    }

    private JSONObject extractCreateVideoParam(Route route) {
        JSONObject originalParam = null;
        Request request = route.request();
        if ("POST".equals(request.method())) {
            // 解析 JSON 数据
            originalParam = JSONObject.parseObject(request.postData());
        }
        return originalParam;
    }

    private String createWork(String authorization, String videoUrl, String imageUrl, String workFilePath, WechatVideoMetaData metaData) {
        // 尺寸
        String querySizeCmd = "ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 " + workFilePath;
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(querySizeCmd);
        detectCmd.execute();
        int width = detectCmd.getWidth();
        int height = detectCmd.getHeight();

        Map<String, Object> params = Maps.newHashMap();
        params.put("title", metaData.getTitle());
        params.put("videoWidth", width);
        params.put("videoHeigth", height);
        params.put("videoUrl", videoUrl);
        params.put("imgUrl", imageUrl);
        params.put("topic", StringUtils.join(metaData.getTags().stream().map(tag -> "#" + tag).collect(Collectors.toList()), ","));
        params.put("videoType", 1);
        params.put("videoKey", System.currentTimeMillis() + "highlight.mp4");
        params.put("imgKey", System.currentTimeMillis() + "highlight.jpg");

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://loong.videostui.com/prod-api/manage/production/create")
                .addHeader("Content-Type", "application/json")
                .addHeader("authorization", authorization)
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSONObject.parseObject(resp);
        if (respObj.getInteger("code") != 200) {
            log.info("create work error, resp: {}, path: {}", resp, workFilePath);
            return null;
        }
        return respObj.getString("data");
    }

    private boolean publishWork(String authorization, String workId) {
        // 获取账号id
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://loong.videostui.com/prod-api/manage/wechatChannelInfo/wxChannels?pageSize=10&pageNum=1&status=3")
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("authorization", authorization)
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSONObject.parseObject(resp);
        if (respObj.getInteger("code") != 200) {
            log.info("get wxUser error, resp: {}, path: {}", resp);
            return false;
        }
        JSONArray wxUsers = respObj.getJSONArray("rows");
        if (CollectionUtils.isEmpty(wxUsers)) {
            log.info("get wxUser empty, wx login expire");
            return false;
        }
        String wcId = wxUsers.getJSONObject(0).getString("wcId");

        // 发布视频
        Map<String, Object> params = Maps.newHashMap();
        params.put("wcIds", Lists.newArrayList(wcId));
        params.put("productionId", workId);
        okhttp3.Request pRequest = new okhttp3.Request.Builder()
                .url("http://loong.videostui.com/prod-api/manage/production/publish")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Content-Type", "application/json")
                .addHeader("authorization", authorization)
                .build();
        String pResp = OkHttpClientUtil.execute(pRequest);
        JSONObject pRespObj = JSONObject.parseObject(pResp);
        if (pRespObj.getInteger("code") != 200) {
            log.info("publish work error, resp: {}", resp);
            return false;
        }
        return true;
    }

    private BrowserType.LaunchOptions buildOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless);

        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
        if (StringUtils.isNotBlank(httpProxy)) {
            options.setProxy(httpProxy);
        }
        return options;
    }


//    private boolean doUpload(String recordPath) {
//        File targetFile = new File(recordPath, "highlight.mp4");
//        String workFilePath = targetFile.getAbsolutePath();
//        String cookiesPath = getAccoutFile().getAbsolutePath();
//        WechatVideoMetaData metaData = FileStoreUtil.loadFromFile(
//                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
//                new TypeReference<WechatVideoMetaData>() {
//                });
//
//        // 1. 从cookies中读取authorization
//        JSONObject cookieObj = FileStoreUtil.loadFromFile(new File(cookiesPath), new TypeReference<JSONObject>() {
//        });
//        Map<String, String> kvMap = cookieObj.getJSONArray("cookies").stream()
//                .collect(Collectors.toMap(obj -> ((JSONObject) obj).getString("name"), obj -> ((JSONObject) obj).getString("value")));
//        String authorization = kvMap.get("Authorization").replace("%20", " ");
//
//        // 2. 上传视频到minio获取下载地址
//        String videoUrl = getVideoUrl(targetFile, metaData);
//        String imageUrl = getImageUrl(targetFile, metaData);
//
//        // 2. create作品
//        String workId = createWork(authorization, videoUrl, imageUrl, workFilePath, metaData);
//        if (StringUtils.isBlank(workId)) {
//            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//        }
//
//        // 3. 发布作品
//        return publishWork(authorization, workId, workFilePath);
//    }
//
//    private String getVideoUrl(File targetFile, WechatVideoMetaData metaData) {
//        String key = String.format(VIDEO_PRESIGNED_URL_KEY, targetFile.getAbsolutePath());
//        String videoUrl = cacheManager.get(key);
//        if (StringUtils.isBlank(videoUrl)) {
//            long curStamp = System.currentTimeMillis();
//            String videoPath = "highlight/" + curStamp + metaData.getTitle() + ".mp4";
//            MinioManager.uploadFileV2(targetFile, videoPath);
//            log.info("upload video {} to minio finish", videoPath);
//            videoUrl = MinioManager.genPresignedObjUrl(videoPath, 6);
//
//            cacheManager.set(key, videoUrl, 6, TimeUnit.HOURS);
//        }
//
//        log.info("get videoUrl: {} success", videoUrl);
//        return videoUrl;
//    }
//
//    private String getImageUrl(File targetFile, WechatVideoMetaData metaData) {
//        String key = String.format(IMAGE_PRESIGNED_URL_KEY, targetFile.getAbsolutePath());
//        String imageUrl = cacheManager.get(key);
//        if (StringUtils.isBlank(imageUrl)) {
//            long curStamp = System.currentTimeMillis();
//            String preViewFilePath = metaData.getPreViewFilePath();
//            String imagePath = "highlight/" + curStamp + new File(preViewFilePath).getName() + ".jpg";
//            MinioManager.uploadFileV2(new File(preViewFilePath), imagePath);
//            imageUrl = MinioManager.genPresignedObjUrl(imagePath, 6);
//
//            cacheManager.set(key, imageUrl, 6, TimeUnit.HOURS);
//        }
//
//        return imageUrl;
//    }
//

//


}
