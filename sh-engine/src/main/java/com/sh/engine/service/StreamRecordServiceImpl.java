package com.sh.engine.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.model.record.TsUrl;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author caiwen
 * @Date 2023 12 19 23 09
 **/
@Component
@Slf4j
public class StreamRecordServiceImpl implements StreamRecordService {
    @Resource
    private MsgSendService msgSendService;

    private static Map<String, String> fakeHeaderMap = Maps.newHashMap();
    ExecutorService TS_DOWNLOAD_POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20480),
            new ThreadFactoryBuilder().setNameFormat("seg-download-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    OkHttpClient CLIENT = new OkHttpClient();
    private static final int BATCH_RECORD_TS_COUNT = 1200;
    private static final int SEG_DOWNLOAD_RETRY = 3;

    static {
        fakeHeaderMap.put("Accept", "*/*");
        fakeHeaderMap.put("Accept-Encoding", "gzip, deflate, br");
        fakeHeaderMap.put("Accept-Language", "zh,zh-TW;q=0.9,en-US;q=0.8,en;q=0.7,zh-CN;q=0.6,ru;q=0.5");
        fakeHeaderMap.put("Origin", "https://www.huya.com");
        fakeHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like " +
                "Gecko) Chrome/83.0.4103.106 Safari/537.36");
    }

    @Override
    public void startRecord(Recorder recorder) {
        RecordTask recordTask = recorder.getRecordTask();
        String streamerName = recordTask.getRecorderName();
        log.info("begin living download: {}, stream: {}", streamerName, recordTask.getStreamUrl());

        livingRecordWithFfmpeg(recorder, recordTask);
    }

    @Override
    public void startDownload(Recorder recorder) {
        RecordTask recordTask = recorder.getRecordTask();
        String streamerName = recordTask.getRecorderName();
        log.info("begin new video download: {}, stream: {}", streamerName, recordTask.getTsUrl().getTsFormatUrl());

        try {
            downloadTs(recorder);
            log.info("new video download finish, stream: {}", streamerName);
        } catch (Exception e) {
            log.error("new video download error, stream: {}", streamerName);
        }
    }

    private void downloadTs(Recorder recorder) throws Exception {
        RecordTask task = recorder.getRecordTask();
        TsUrl tsUrl = task.getTsUrl();
        String dirName = recorder.getSavePath();

        Integer total = tsUrl.getCount();
        List<Integer> segIndexes = Lists.newArrayList();
        for (int i = 1; i < total + 1; i++) {
            segIndexes.add(i);
        }

        int videoIndex = 1;
        AtomicInteger finishCount = new AtomicInteger();
        for (List<Integer> batchIndexes : Lists.partition(segIndexes, BATCH_RECORD_TS_COUNT)) {
            File targetMergedVideo = new File(dirName, "P" + videoIndex + ".mp4");
            if (targetMergedVideo.exists()) {
                log.info("merge video: {} existed, skip this batch", targetMergedVideo.getAbsolutePath());
                finishCount.addAndGet(Math.min(total - BATCH_RECORD_TS_COUNT, BATCH_RECORD_TS_COUNT));
                videoIndex++;
                continue;
            }

            CountDownLatch downloadLatch = new CountDownLatch(Math.min(total - finishCount.get(), BATCH_RECORD_TS_COUNT));
            // 每一个BATCH_RECORD_TS_COUNT去下载
            for (Integer i : batchIndexes) {
                String segTsUrl = tsUrl.genTsUrl(i);
                int finalI1 = i;
                CountDownLatch finalDownloadLatch = downloadLatch;
                CompletableFuture.supplyAsync(() -> {
                            File targetFile = new File(dirName, "seg-" + finalI1 + ".ts");
                            if (targetFile.exists()) {
                                log.info("ts file existed, path: {}", targetFile.getAbsolutePath());
                                return true;
                            }
                            return downloadTsSeg(segTsUrl, targetFile);
                        }, TS_DOWNLOAD_POOL)
                        .whenComplete((isSuccess, throwable) -> {
                            finalDownloadLatch.countDown();
                            finishCount.getAndIncrement();
                        });
            }

            // 等待一批视频下载完成，对视频进行合并并删除
            downloadLatch.await();
            int s = (videoIndex - 1) * BATCH_RECORD_TS_COUNT + 1;
            int e = Math.min(videoIndex * BATCH_RECORD_TS_COUNT, total);
            boolean mergedSuccess = mergeVideos(s, e, targetMergedVideo);
            if (mergedSuccess) {
                deleteDownloadedVideos(s, e, dirName);
            }
            videoIndex++;
        }
    }

    private boolean downloadTsSeg(String targetUrl, File targetFile) {
        for (int i = 0; i < SEG_DOWNLOAD_RETRY; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
            }

            Request request = new Request.Builder().url(targetUrl).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(response.body().bytes());
                    }
                    log.info("Seg Video Download finish, filePath: {}", targetFile.getAbsolutePath());
                    return true;
                } else {
                    log.error("Seg Video download failed, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY);
                }
            } catch (IOException e) {
                log.error("Seg Video calling error, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY, e);
            }
        }
        return false;
    }

    private boolean mergeVideos(int start, int end, File targetVideo) {
        // 1. 写入代合并的文件路径
        List<String> mergeTsPaths = Lists.newArrayList();
        for (int i = start; i <= end; i++) {
            File segFile = new File(targetVideo.getParent(), "seg-" + i + ".ts");
            if (segFile.exists() && FileUtils.sizeOf(segFile) > 0) {
                // 判断一些文件是否存在，如果不存在不合并
                mergeTsPaths.add("file " + segFile.getAbsolutePath());
            }
        }

        File mergeListFile = new File(targetVideo.getParent(), "merge.txt");
        try {
            IOUtils.write(StringUtils.join(mergeTsPaths, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
//        String command = "-f concat -safe 0 -i " + mergeListFile.getAbsolutePath() + " -c:v libx264 -c:a libfdk_aac " + targetPath;
        String command = "-f concat -safe 0 -i " + mergeListFile.getAbsolutePath() + " -c:v libx264 -crf 25 -preset superfast -c:a libfdk_aac -r 30 " + targetPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);

        msgSendService.send("开始压缩视频... 路径为：" + targetVideo.getAbsolutePath());
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            msgSendService.send("压缩视频完成！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video success, path: {}", targetPath);
            return true;
        } else {
            msgSendService.send("压缩视频失败！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video fail, path: {}", targetPath);
            return false;
        }
    }

    private static void deleteDownloadedVideos(int start, int send, String dirName) {
        // 删除下载的视频文件
        for (int i = start; i <= send; i++) {
            File file = new File(dirName, "seg-" + i + ".ts");
            file.delete();
        }
    }


    /**
     * 使用ffmpeg开始拉流
     *
     * @param recorder
     * @param recordTask
     */
    private boolean livingRecordWithFfmpeg(Recorder recorder, RecordTask recordTask) {
        File fileToDownload = new File(recorder.getSavePath(),
                recordTask.getRecorderName() + "-part-%03d." + recorder.getVideoExt());

        // 1. 生成拉流命令
//        String command = genFfmpegCmd(recordTask.getStreamUrl(), fileToDownload.getAbsolutePath());
        String command = buildFfmpegCmd(recordTask.getStreamUrl(), fileToDownload.getAbsolutePath());

        // 2. ffmpegCmd命令放到线程池中
        return doFfmpegCmd(new FfmpegCmd(command), recorder);
    }

    private String buildFfmpegCmd(String streamUrl, String downloadFileName) {
        String userAgent = "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36";
        List<String> commands = Lists.newArrayList(
                 "-y",
                "-v", "verbose",
                "-rw_timeout", "15000000",
                "-loglevel", "error",
                "-hide_banner",
                "-user_agent", "\"" + userAgent + "\"",
                "-protocol_whitelist", "rtmp,crypto,file,http,https,tcp,tls,udp,rtp",
                "-thread_queue_size", "1024",
                "-analyzeduration", "2147483647",
                "-probesize", "2147483647",
                "-fflags", "+discardcorrupt",
                "-i", "\"" + streamUrl + "\"",
                "-bufsize", "5000k",
                "-sn", "-dn",
                "-reconnect_delay_max", "30",
                "-reconnect_streamed", "-reconnect_at_eof",
                "-max_muxing_queue_size", "64",
                "-correct_ts_overflow", "1",
//                "-c:v", "libx264",
                "-r", "30",
                "-c:v", "copy",
                "-c:a", "libfdk_aac",
                "-map", "0",
                "-f", "segment",
                "-segment_time", ConfigFetcher.getInitConfig().getSegmentDuration() + "",
                "-segment_start_number", "1",
                "-segment_format", "mp4",
                "-movflags", "+faststart",
                "-reset_timestamps", "1",
                "\"" + downloadFileName + "\""
        );
        return StringUtils.join(commands, " ");

    }

    private boolean doFfmpegCmd(FfmpegCmd ffmpegCmd, Recorder recorder) {
        String recorderName = recorder.getRecordTask().getRecorderName();
        // 进行拉流，长时间阻塞在这
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("download stream completed, recordName: {}, savePath: {}, code: {}", recorderName, recorder.getSavePath(), resCode);
            return true;
        } else {
            log.error("download stream fail, recordName: {}, savePath: {}, code: {}", recorderName, recorder.getSavePath(), resCode);
            return false;
        }
    }

    public static void main(String[] args) {
        System.setProperty("http.proxySet", "true");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "10809");
        StreamRecordServiceImpl streamRecordService = new StreamRecordServiceImpl();
        streamRecordService.startDownload(Recorder.initRecorder(RecordTask.builder()
                .tsUrl(TsUrl.builder()
                        .tsFormatUrl("https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/vod/20231228/585/250550585/REGL_E8F3995E_250550585_1.smil/original/both/seg-%s.ts")
                        .count(61)
                        .build())
                .recorderName("Zeus")
                .timeV("2023-12-30 10:10:00")
                .build()));
    }
}
