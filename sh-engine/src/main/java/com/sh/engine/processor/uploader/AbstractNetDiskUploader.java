package com.sh.engine.processor.uploader;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.repo.StreamerRepoService;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.service.NetDiskCopyService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Resource;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2025 03 16 16 45
 **/
@Slf4j
public abstract class AbstractNetDiskUploader extends Uploader {
    @Resource
    private NetDiskCopyService netDiskCopyService;
    @Resource
    private StreamerRepoService streamerRepoService;

    @Override
    public void initUploader() {
        // 检查一下文件
        boolean isExisted = netDiskCopyService.checkBasePathExist(UploadPlatformEnum.of(getType()));
        if (!isExisted) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }
        log.info(getType() + " uploader init success");
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        List<File> files = VideoFileUtil.listRecordedFiles(recordPath)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(files)) {
            return true;
        }
        for (File targetFile : files) {
            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(recordPath, targetFile);
            if (remoteSeverVideo != null) {
                log.info("video has been uploaded to {}, file: {}", getType(), targetFile.getAbsolutePath());
                continue;
            }
            remoteSeverVideo = uploadFile(targetFile);
            saveUploadedVideo(recordPath, remoteSeverVideo);
        }


        return true;
    }

    private RemoteSeverVideo uploadFile(File targetFile) {
        UploadPlatformEnum platform = UploadPlatformEnum.of(getType());
        netDiskCopyService.copyFileToNetDisk(platform, targetFile);

        // 部分主播会被网盘判定为违规，按配置对上传后的文件后缀进行重命名（默认不重命名）
        StreamerConfig config = streamerRepoService.getByName(StreamerInfoHolder.getCurStreamerName());
        String renameSuffix = config == null ? null : config.getNetDiskFileRenameSuffix();
        if (StringUtils.isNotBlank(renameSuffix)) {
            try {
                netDiskCopyService.renameFileSuffix(platform, targetFile, renameSuffix);
            } catch (Exception e) {
                log.error("rename file suffix failed, file: {}, suffix: {}", targetFile.getAbsolutePath(), renameSuffix, e);
            }
        }
        return new RemoteSeverVideo(targetFile.getAbsolutePath(), targetFile.getAbsolutePath());
    }

    /**
     * 最大并行上传数量
     */
    @Override
    public int getMaxUploadParallel() {
        return 1;
    }
}
