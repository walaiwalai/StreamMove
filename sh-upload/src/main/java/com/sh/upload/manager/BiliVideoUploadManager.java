//package com.sh.upload.manager;
//
//import cn.hutool.core.io.FileUtil;
//import cn.hutool.http.HttpUtil;
//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.JSONObject;
//import com.google.common.collect.ImmutableMap;
//import com.google.common.collect.Lists;
//import com.google.common.collect.Maps;
//import com.sh.config.manager.ConfigManager;
//import com.sh.config.model.config.StreamerInfo;
//import com.sh.config.model.config.UploadPersonInfo;
//import com.sh.config.model.stauts.FileStatusModel;
//import com.sh.config.model.video.*;
//import com.sh.engine.manager.StatusManager;
//import com.sh.engine.model.record.RecordTask;
//import com.sh.upload.constant.UploadConstant;
//import com.sh.upload.model.BiliPreUploadInfoModel;
//import com.sh.upload.model.BiliVideoUploadModel;
//import com.sh.upload.model.user.BiliUploadUser;
//import com.sh.upload.model.web.BiliPreUploadRespose;
//import com.sh.upload.service.BiliVideoUploadServiceImpl;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.lang3.BooleanUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.commons.text.StringSubstitutor;
//import org.apache.http.entity.InputStreamEntity;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.File;
//import java.io.FileInputStream;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.stream.Collectors;
//
///**
// * 上传视频文件
// *
// * @author caiWen
// * @date 2023/1/25 18:58
// */
//@Component
//@Slf4j
//public class BiliVideoUploadManager {
//    @Resource
//    ConfigManager configManager;
//    @Resource
//    StatusManager statusManager;
//    @Resource
//    BiliVideoUploadServiceImpl biliVideoUploadService;
//
//    public static final Map<String, String> BILI_HEADERS = Maps.newHashMap();
//    private static final String BILI_PRE_URL
//            = "https://member.bilibili.com/preupload?access_key=${accessToken}&mid=${mid}&profile=ugcfr%2Fpc3";
//    /**
//     * 视频上传分块大小为5M
//     */
//    private static final long CHUNK_SIZE = 1024 * 1024 * 10L;
//
//    /**
//     * 录播线程池
//     */
//    private final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
//            3, 6, 600, TimeUnit.SECONDS, new ArrayBlockingQueue<>(120),
//            Executors.defaultThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy()
//    );
//
//    static {
//        BILI_HEADERS.put("Connection", "alive");
//        BILI_HEADERS.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
//        BILI_HEADERS.put("User-Agent",
//                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) "
//                        + "Chrome/109.0.0.0 Mobile Safari/537.36 Edg/109.0.1518.55");
//        BILI_HEADERS.put("Accept-Encoding", "gzip,deflate");
//    }
//
//
//    public void upload(BiliVideoUploadModel uploadModel) {
//        if (!checkNeedUpload(uploadModel)) {
//            return;
//        }
//
//        String dirName = uploadModel.getDirName();
//        try {
//            log.info("begin upload, dirName: {}", dirName);
//            // 1. 锁住上传视频
//            statusManager.lockRecordForSubmission(dirName);
//
//            // 2.加载文件夹状态并注入
//            insertValueFromFileStatus(uploadModel);
//
//            // 3. 获取本地视频文件
//            log.info("get local videos, path: {}", dirName);
//            List<LocalVideoPart> localVideoParts = fetchLocalVideos(dirName, uploadModel);
//            if (CollectionUtils.isEmpty(localVideoParts)) {
//                log.warn("{} has no videos", dirName);
//                return;
//            }
//
//            // 4.预上传视频
//            log.info("Start to upload videoParts ...");
//            List<RemoteVideoPart> remoteVideoParts = uploadVideoParts(localVideoParts, uploadModel);
//            log.info("Upload videoParts END, remoteVideos: {}", JSON.toJSONString(remoteVideoParts));
//            if (CollectionUtils.isNotEmpty(uploadModel.getSucceedUploaded())) {
//                log.info("Found succeed uploaded videos ... Concat ...");
//                remoteVideoParts.addAll(uploadModel.getSucceedUploaded());
//            }
//            // 4.1 给需要上传的视频文件命名
//            for (int i = 0; i < remoteVideoParts.size(); i++) {
//                remoteVideoParts.get(i).setTitle("P" + (i + 1));
//            }
//
//            // 5.post视频
//            log.info("Try to post Videos: {}", JSON.toJSONString(remoteVideoParts));
//            postVideos(remoteVideoParts);
//            log.info("upload video success.");
//
//            // 6.更新文件属性
//            FileStatusModel.updateToFile(dirName, FileStatusModel.builder().isPost(true).build());
//        } catch (Exception e) {
//            log.error("Upload video fail, dirName: {}", dirName, e);
//        } finally {
//            statusManager.releaseRecordForSubmission(dirName);
//        }
//    }
//
//    private void insertValueFromFileStatus(BiliVideoUploadModel uploadModel) {
//        File statusFile = new File(uploadModel.getDirName(), "fileStatus.json");
//        if (!statusFile.exists()) {
//            return;
//        }
//        // 1.加载fileStatus.json文件
//        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(uploadModel.getDirName());
//
//        // 2. 注入配置
//        UploadVideoPair videoParts = fileStatusModel.getVideoParts();
//        uploadModel.setSucceedUploaded(Optional.ofNullable(videoParts)
//                .map(UploadVideoPair::getSucceedUploadedVideos)
//                .orElse(Lists.newArrayList()));
//        uploadModel.setIsUploadFail(fileStatusModel.getIsFailed());
//
//        if (BooleanUtils.isTrue(fileStatusModel.getIsFailed())) {
//            uploadModel.setFailUpload(Optional.ofNullable(videoParts)
//                    .map(UploadVideoPair::getFailedUploadVideo).orElse(null));
//            uploadModel.setSucceedTotalLength(Optional.ofNullable(videoParts)
//                    .map(UploadVideoPair::getFailedUploadVideo)
//                    .map(FailedUploadVideo::getSucceedTotalLength)
//                    .orElse(0)
//            );
//            uploadModel.setSucceedTotalLength(Optional.ofNullable(videoParts)
//                    .map(UploadVideoPair::getFailedUploadVideo)
//                    .map(FailedUploadVideo::getSucceedUploadChunk)
//                    .orElse(0)
//            );
//            uploadModel.setUploadStart(Optional.ofNullable(videoParts)
//                    .map(UploadVideoPair::getFailedUploadVideo)
//                    .map(FailedUploadVideo::getUploadStartTime)
//                    .orElse(0L)
//            );
//            uploadModel.setDeadline(Optional.ofNullable(videoParts)
//                    .map(UploadVideoPair::getFailedUploadVideo)
//                    .map(FailedUploadVideo::getDeadline)
//                    .orElse(0L)
//            );
//        }
//    }
//
//    private List<LocalVideoPart> fetchLocalVideos(String dirName, BiliVideoUploadModel uploadModel) {
//        long videoPartLimitSize = uploadModel.getVideoPartLimitSizeInput() * 1024L * 1024L;
//        Integer videoIndex = 0;
//
//        List<String> subFileNames = FileUtil.listFileNames(dirName);
//        List<LocalVideoPart> localVideoParts = Lists.newArrayList();
//        for (String subFileName : subFileNames) {
//            if (StringUtils.equals("fileStatus.json", subFileName)) {
//                // 只处理视频文件
//                continue;
//            }
//
//            File subVideoFile = new File(dirName, subFileName);
//            String fullPath = subVideoFile.getAbsolutePath();
//            // 过小的视频文件不上场
//            long fileSize = FileUtil.size(subVideoFile);
//            if (fileSize < videoPartLimitSize) {
//                log.info("video size too small, give up upload, fileName: {}, size: {}, limitSize: {}", subFileName,
//                        fileSize, videoPartLimitSize);
//                continue;
//            }
//
//            // 已经上传的文件不重复上传
//            if (CollectionUtils.isNotEmpty(uploadModel.getSucceedUploaded())) {
//                List<String> succeedPaths = uploadModel.getSucceedUploaded().stream().map(
//                        SucceedUploadVideo::getLocalFilePath).collect(Collectors.toList());
//                if (succeedPaths.contains(fullPath)) {
//                    log.info("video has been uploaded, path: {}", fullPath);
//                    continue;
//                }
//            }
//
//
//            if (uploadModel.getIsUploadFail()) {
//                // 处理上传失败的视频
//                FailedUploadVideo failedUploadVideo = uploadModel.getFailUpload();
//                boolean isCurFailedPart = Objects.equals(Optional.ofNullable(failedUploadVideo)
//                        .map(FailedUploadVideo::getLocalFilePath)
//                        .orElse(null), fullPath);
//                Long failDeadLine = Optional.ofNullable(failedUploadVideo).map(FailedUploadVideo::getDeadline)
//                        .orElse(0L);
//                Long failedUploadStartTime = Optional.ofNullable(failedUploadVideo).map(
//                        FailedUploadVideo::getUploadStartTime)
//                        .orElse(0L);
//                boolean isReUploadFailVideo = isCurFailedPart && (failDeadLine > failedUploadStartTime + 7200L);
//                if (isReUploadFailVideo) {
//                    log.info("push upload error video to videoParts");
//                    localVideoParts.add(LocalVideoPart.builder()
//                            .isFailed(true)
//                            .localFilePath(Optional.ofNullable(uploadModel.getFailUpload())
//                                    .map(FailedUploadVideo::getLocalFilePath)
//                                    .orElse(null))
//                            .title("P" + (videoIndex + 1))
//                            .desc("")
//                            .fileSize(fileSize)
//                            .build());
//
//                }
//            } else {
//                localVideoParts.add(LocalVideoPart.builder()
//                        .isFailed(false)
//                        .localFilePath(fullPath)
//                        .title("P" + (videoIndex + 1))
//                        .desc("")
//                        .fileSize(fileSize)
//                        .build());
//                videoIndex++;
//            }
//        }
//        log.info("Final videoParts: {}", JSON.toJSONString(localVideoParts));
//        return localVideoParts;
//    }
//
//    /**
//     * 上传分段视频
//     *
//     * @param localVideoParts
//     * @param uploadModel
//     * @return
//     */
//    private List<RemoteVideoPart> uploadVideoParts(List<LocalVideoPart> localVideoParts,
//            BiliVideoUploadModel uploadModel) {
//        log.info("uploadVideoPart Start ...");
//        List<RemoteVideoPart> remoteVideos = Lists.newArrayList();
//        for (int i = 0; i < localVideoParts.size(); i++) {
//            LocalVideoPart localVideoPart = localVideoParts.get(i);
//            try {
//                String uploadUrl, completeUploadUrl, serverFileName;
//                if (localVideoPart.isFailed()) {
//                    uploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getUploadUrl()).orElse("");
//                    completeUploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getCompleteUploadUrl()).orElse(
//                            "");
//                    serverFileName = Optional.ofNullable(uploadModel.getFailUpload().getServerFileName()).orElse("");
//                } else {
//                    // 进行预上传
//                    UploadVideoPartResult preUploadData = getPreUploadData(uploadModel);
//                    uploadUrl = preUploadData.getUploadUrl();
//                    completeUploadUrl = preUploadData.getCompleteUploadUrl();
//                    serverFileName = preUploadData.getServerFileName();
//                }
//                log.debug("path: {}, serverFileName: {}, uploadUrl: {}, completeUploadUrl: {}",
//                        uploadModel.getDirName(), serverFileName, uploadUrl, completeUploadUrl);
//
//                // 进行上传
//                //                RemoteVideoPart videoOnServer = uploadVideoPart(localVideoPart.getFileSize(),
//                //                        localVideoPart.getLocalFilePath(), localVideoPart.getTitle(),
//                //                        localVideoPart.getDesc(),
//                //                        uploadUrl, completeUploadUrl, serverFileName, localVideoPart.isFailed());
//                //                remoteVideos.add(videoOnServer);
//
//                // 写文件状态
//                //                syncStatus(uploadModel, localVideoPart, videoOnServer);
//
//            } catch (Exception e) {
//                log.error("upload video part fail, localVideoPart: {}", localVideoPart.getLocalFilePath());
//                statusManager.releaseRecordForSubmission(uploadModel.getDirName());
//            }
//        }
//        return remoteVideos;
//    }
//
//
//    /**
//     * 更新状态到fileStatus.json
//     *
//     * @param uploadModel
//     * @param localVideoPart
//     * @param remoteVideoPart
//     */
//    private void syncStatus(BiliVideoUploadModel uploadModel, LocalVideoPart localVideoPart,
//            RemoteVideoPart remoteVideoPart) {
//        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(uploadModel.getDirName());
//        UploadVideoPair videoParts = fileStatusModel.getVideoParts();
//        List<SucceedUploadVideo> exsitedSuccessUploadVideoParts = Optional.ofNullable(
//                videoParts.getSucceedUploadedVideos()).orElse(Lists.newArrayList());
//
//        boolean isAlreadyInStatus = exsitedSuccessUploadVideoParts.stream().anyMatch(
//                succeedUploadVideo -> StringUtils.equals(succeedUploadVideo.getLocalFilePath(),
//                        localVideoPart.getLocalFilePath()));
//
//        if (isAlreadyInStatus) {
//            log.info("Found Exist Video {}", localVideoPart.getLocalFilePath());
//            return;
//        }
//
//        SucceedUploadVideo newSucceedPart = new SucceedUploadVideo();
//        newSucceedPart.setLocalFilePath(localVideoPart.getLocalFilePath());
//        newSucceedPart.setFileName(remoteVideoPart.getFileName());
//        newSucceedPart.setDesc(remoteVideoPart.getDesc());
//        newSucceedPart.setTitle(remoteVideoPart.getTitle());
//        exsitedSuccessUploadVideoParts.add(newSucceedPart);
//
//        FileStatusModel.updateToFile(uploadModel.getDirName(), fileStatusModel);
//    }
//
//    /**
//     * 预上传接口请求
//     *
//     * @param uploadModel
//     * @return
//     */
//    public UploadVideoPartResult getPreUploadData(BiliVideoUploadModel uploadModel) {
//        Map<String, String> urlParams = ImmutableMap.of(
//                "accessToken", configManager.getConfig().getPersonInfo().getAccessToken(),
//                "mid", configManager.getConfig().getPersonInfo().getMid().toString()
//        );
//        StringSubstitutor sub = new StringSubstitutor(urlParams);
//        String preUrl = sub.replace(BILI_PRE_URL);
//
//        String resp = HttpUtil.get(preUrl);
//        BiliPreUploadRespose biliPreUploadRespose = JSON.parseObject(resp, BiliPreUploadRespose.class);
//
//        if (biliPreUploadRespose.getOK() != 1) {
//            log.error("get preUpload fail, resp: {}", resp);
//            return null;
//        }
//
//        log.debug("get preUpload resp: {}", resp);
//        String uploadUrl = biliPreUploadRespose.getUrl();
//        String completeUploadUrl = biliPreUploadRespose.getComplete();
//        String serverFileName = biliPreUploadRespose.getFilename();
//
//        String[] params = StringUtils.split(uploadUrl, "?")[1].split("\\u0026");
//        Map<String, String> paramMap = Maps.newHashMap();
//        for (String param : params) {
//            String[] split = param.split("=");
//            paramMap.put(split[0], split[1]);
//        }
//
//        // 拼接一些参数到uploadModel
//        String deadline = paramMap.get("deadline");
//        if (StringUtils.isNotBlank(deadline)) {
//            uploadModel.setDeadline(Long.valueOf(deadline));
//        }
//        String uploadstart = paramMap.get("uploadstart");
//        if (StringUtils.isNotBlank(uploadstart)) {
//            uploadModel.setUploadStart(Long.valueOf(uploadstart));
//        }
//
//        return new UploadVideoPartResult(uploadUrl, completeUploadUrl, serverFileName);
//    }
//
//    private void postVideos(List<RemoteVideoPart> remoteVideoParts) {
//
//        return;
//    }
//
//
//    private RemoteVideoPart uploadVideoPart(BiliVideoUploadModel uploadModel, long fileSize, String localFilePath,
//            String title, String desc, String uploadUrl, String completeUploadUrl, String serverFileName,
//            boolean isResume) {
//        //        MD5 fileHash = MD5.create();
//        //        int chunkNum = (int) Math.ceil(fileSize / CHUNK_SIZE);
//        //        Integer successUploadedChunks = isResume ? uploadModel.getSucceedUploadChunk() : -1;
//        //        IOUtils.
//        //
//        //
//        //            const fileStream = fs.createReadStream(path)
//        //        let readBuffers:Buffer = Buffer.from('')
//        //        let readLength = 0
//        //        let totalReadLength = 0
//        //        let nowChunk = 0
//        //        this.logger.info(`开始上传 $ {path}，文件大小：$ {fileSize}，分块数量：$ {chunkNum}，succeedUploa：$
//        //        {successUploadedChunks}`)
//        //        return null;
//        return null;
//    }
//
//
//    public List<FailUploadVideoChunk> uploadVideoChunks(String dirName, File videoFile) throws Exception {
//        FileInputStream fis = new FileInputStream(videoFile);
//        String videoName = videoFile.getName();
//        long fileSize = fis.getChannel().size();
//
//        // 1.获得预加载上传的b站视频地址信息
//        String biliCookies = configManager.getConfig().getPersonInfo().getBiliCookies();
//        BiliPreUploadModel biliPreUploadModel = new BiliPreUploadModel(videoName, fileSize,
//                biliCookies);
//        BiliPreUploadInfoModel biliPreUploadInfo = biliPreUploadModel.getBiliPreUploadInfo();
//        Integer chunkSize = biliPreUploadInfo.getChunk_size();
//        String uploadUrl = biliPreUploadModel.getUploadUrl();
//        long deadline = System.currentTimeMillis() + biliPreUploadInfo.getTimeout() * 60 * 1000;
//
//
//        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);
//        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
//
//        // 2.进行视频分块上传
//        CountDownLatch countDownLatch = new CountDownLatch(partCount);
//        Map<String, String> uploadChunkHeaders = buildUploadChunkHeader(biliCookies, biliPreUploadInfo);
//        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
//        JSONObject extension = new JSONObject();
//        extension.put("uploadUrl", uploadUrl);
//        extension.put("uploadId", biliPreUploadModel.getUploadId());
//        extension.put("Cookie", biliCookies);
//        extension.put("upos_uri", biliPreUploadInfo.getUpos_uri());
//
//        for (int i = 0; i < partCount; i++) {
//            long uploadStartTime = System.currentTimeMillis();
//            //当前分段起始位置
//            long curChunkStart = i * chunkSize;
//            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
//            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : chunkSize;
//            long curChunkEnd = curChunkStart + curChunkSize;
//
//            fis.skip(curChunkStart);
//            int finalI = i;
//            CompletableFuture.supplyAsync(() -> {
//                return biliVideoUploadService.uploadChunk(
//                        new InputStreamEntity(fis, curChunkSize), finalI, partCount, curChunkSize, curChunkStart,
//                        curChunkEnd, fileSize, countDownLatch, extension);
//            }, UPLOAD_POOL)
//                    .whenComplete((isSuccess, throwbale) -> {
//                        if (!isSuccess) {
//                            // 其中有一个分块上传失败的话，解锁当前目录下的上传状态
//                            statusManager.releaseRecordForSubmission(dirName);
//                            FailUploadVideoChunk failUploadVideoChunk = new FailUploadVideoChunk();
//                            failUploadVideoChunk.setChunkStart(curChunkStart);
//                            failUploadVideoChunk.setCurChunkSize(curChunkSize);
//                            failUploadVideoChunk.setChunkNo(finalI);
//                            failUploadVideoChunks.add(failUploadVideoChunk);
//                        }
//                    });
//        }
//
//        countDownLatch.await(1, TimeUnit.HOURS);
//        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
//            log.info("video upload success, videoName: {}", videoName);
//        } else {
//            log.error("video upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
//                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
//        }
//
//        return failUploadVideoChunks;
//    }
//
//
//
//    private Map<String, String> buildUploadChunkHeader(String biliCookies, BiliPreUploadInfoModel biliPreUploadInfo) {
//        Map<String, String> uploadChunkHeaders = Maps.newHashMap();
//        uploadChunkHeaders.put("Accept", "*/*");
//        uploadChunkHeaders.put("Accept-Encoding", "gzip, deflate, br");
//        uploadChunkHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
//        uploadChunkHeaders.put("Origin", "https://member.bilibili.com");
//        uploadChunkHeaders.put("Referer", "https://member.bilibili.com/video/upload.html");
//        uploadChunkHeaders.put("Cookie", biliCookies);
//        uploadChunkHeaders.put("X-Upos-Auth", biliPreUploadInfo.getUpos_uri());
//        return uploadChunkHeaders;
//    }
//
//
//    public static void main(String[] args) throws Exception {
//        String filePath = "D:\\360MoveData\\Users\\caiwe\\Desktop\\2part-000.mp4";
//        File file = new File(filePath);
//        System.out.println(file.getName());
//        //        String uploadUrl = "ttp://upcdn-szbd.bilivideo.com/vs814/upload3/3a071c9239bbecac60fd294e228b30c1/?deadline"
////                + "=1675058218\\u0026filename=n23012814ti5pd98uqho73jhbgm5tuf3\\u0026os=bili\\u0026profile=ugcfr"
////                + "%2Fpc3\\u0026uid=3493088808930053\\u0026uip=122.235.80"
////                + ".226\\u0026upcdn=szbd\\u0026uploadstart=1674914218\\u0026uport=2989\\u0026use_dqp=0";
////        String replace = uploadUrl.replace("\\u0026", "&");
////
////
////        System.out.println(replace);
//
//        //        String serverFileName = "n23012706974ojquj0xcz3nb5kq5k582";
////        BiliVideoUploadManager biliVideoUploadManager = new BiliVideoUploadManager();
////        biliVideoUploadManager.uploadPartVideo(replace, serverFileName, new File(filePath), false);
//
//    }
//
//
//    public boolean checkNeedUpload(BiliVideoUploadModel uploadModel) {
//        // 不上传本地直接返回
//        if (BooleanUtils.isNotTrue(uploadModel.getUploadLocalFile())) {
//            log.info("user config => {} uploadLocalFile {}. Upload Give up...", uploadModel.getRecorderName(),
//                    uploadModel.getUploadLocalFile());
//            return false;
//        }
//
//        // 没有对应文件夹直接返回
//        if (StringUtils.isNotBlank(uploadModel.getDirName())) {
//            log.error("filePath not exsited for: {}.", uploadModel.getRecorderName());
//            return false;
//        }
//
//        // 当前视频任务是否在上传中
//        if (statusManager.isRecordOnSubmission(uploadModel.getDirName())) {
//            log.error("videos in {} is on submission, do upload repeat", uploadModel.getDirName());
//            return false;
//        }
//        return true;
//    }
//
//
//    private void syncUserInfo(BiliUploadUser uploadUser) {
//        configManager.syncUploadPersonInfoToConfig(
//                UploadPersonInfo.builder()
//                        .accessToken(uploadUser.getAccessToken())
//                        .mid(uploadUser.getMid())
//                        .refreshToken(uploadUser.getRefreshToken())
//                        .expiresIn(uploadUser.getExpiresIn())
//                        .nickname(uploadUser.getNickName())
//                        .tokenSignDate(uploadUser.getTokenSignTime())
//                        .build()
//        );
//    }
//
//    public BiliVideoUploadModel convertToUploadModel(RecordTask recordTask) {
//        StreamerInfo streamerInfo = recordTask.getStreamerInfo();
//        return BiliVideoUploadModel.builder()
//                .appSecret(UploadConstant.BILI_VIDEO_UPLOAD_APP_SECRET)
//                .videoPartLimitSizeInput(Optional.ofNullable(
//                        configManager.getConfig().getStreamerHelper().getVideoPartLimitSize())
//                        .orElse(100))
//                .dirName(recordTask.getDirName())
//                .mid(Optional.ofNullable(configManager.getConfig().getPersonInfo().getMid())
//                        .orElse(0L))
//                .accessToken(Optional.ofNullable(
//                        configManager.getConfig().getPersonInfo().getAccessToken()).orElse(
//                        "xxx"))
//                .copyright(Optional.ofNullable(streamerInfo.getCopyright()).orElse(2))
//                // todo 支持封面
//                .cover("")
//                .desc(Optional.ofNullable(streamerInfo.getDesc()).orElse("视频投稿"))
//                .noRePrint(0)
//                .openElec(1)
//                .source(Optional.ofNullable(streamerInfo.getSource())
//                        .orElse(genDefaultDesc(recordTask)))
//                .tags(streamerInfo.getTags())
//                .tid(streamerInfo.getTid())
//                .title(genVideoTitle(recordTask))
//                .dynamic(Optional.ofNullable(recordTask.getStreamerInfo().getDynamic())
//                        .orElse(genDefaultDesc(recordTask)))
//                .uploadLocalFile(Optional.ofNullable(streamerInfo.getUploadLocalFile()).orElse(true))
//                .recorderName(recordTask.getRecorderName())
//                .deadline(0L)
//                .uploadStart(0L)
//                .succeedUploaded(Lists.newArrayList())
//                .succeedUploadChunk(0)
//                .succeedTotalLength(0)
//                .build();
//
//    }
//
//    private String genVideoTitle(RecordTask recordTask) {
//        Map<String, String> paramsMap = Maps.newHashMap();
//        paramsMap.put("time", recordTask.getTimeV());
//        paramsMap.put("name", recordTask.getRecorderName());
//        if (StringUtils.isNotBlank(recordTask.getStreamerInfo().getTemplateTitle())) {
//            StringSubstitutor sub = new StringSubstitutor(paramsMap);
//            return sub.replace(recordTask.getStreamerInfo().getTemplateTitle());
//        } else {
//            return recordTask.getRecorderName() + " " + recordTask.getTimeV() + " " + "录播";
//        }
//    }
//
//    private String genDefaultDesc(RecordTask recordTask) {
//        return recordTask.getRecorderName() +
//                " 直播间：" + recordTask.getStreamerInfo().getRoomUrl();
//    }
//}
