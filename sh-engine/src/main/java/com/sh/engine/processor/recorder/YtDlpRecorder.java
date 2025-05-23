package com.sh.engine.processor.recorder;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.ffmpeg.YtDlpDownloadProcessCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.util.Date;

/**
 * 采用yt-dlp下载
 *
 * @Author caiwen
 * @Date 2025 02 09 16 09
 **/
@Slf4j
public class YtDlpRecorder extends Recorder {
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    private String videoUrl;

    public YtDlpRecorder(Date regDate, String videoUrl) {
        super(regDate);
        this.videoUrl = videoUrl;
    }

    @Override
    public void doRecord(String savePath) {
        YtDlpDownloadProcessCmd ytDlpDownloadProcessCmd = new YtDlpDownloadProcessCmd(buildCmd(savePath));
        ytDlpDownloadProcessCmd.execute(24 * 3600);

        if (!ytDlpDownloadProcessCmd.isNormalExit()) {
            log.info("download video failed, path: {}", savePath);
            FileUtils.deleteQuietly(new File(savePath));
            throw new StreamerRecordException(ErrorEnum.RECORD_ERROR);
        }
    }

    private String buildCmd(String savePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("yt-dlp -f \"bv*+ba/b\" ");

        // cookies文件
        File cookiesFile = new File(accountSavePath, "youtube-cookies.txt");
        if (cookiesFile.exists()) {
            sb.append("--cookies " + cookiesFile.getAbsolutePath() + " ");
        }

        String targetPath = new File(savePath, "%(title)s.%(ext)s").getAbsolutePath();
        sb.append("-o " + targetPath + " ");

        sb.append(videoUrl);

        return sb.toString();
    }
}
