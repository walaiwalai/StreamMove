package com.sh.engine.processor.checker;

import com.sh.config.manager.MinioManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.MinioRecorder;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 从minio看是否有直播被上传
 *
 * @Author caiwen
 * @Date 2024 10 19 16 31
 **/
@Component
@Slf4j
public class MinioRoomChecker extends AbstractRoomChecker {
    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String dirPath = roomUrl.substring(roomUrl.lastIndexOf(":") + 1);

        // 获取mino下指定dirPath最近上传的文件夹
        List<String> timeVs = MinioManager.getFolderNames(dirPath + "/");
        Date date = timeVs.stream()
                // 已经上传完成的
                .filter(timeV -> MinioManager.doesFileExist(dirPath + "/" + timeV + "/" + "finish-flag.txt"))
                .map(timeV -> DateUtil.covertStr2Date(timeV, DateUtil.YYYY_MM_DD_HH_MM_SS_V2))
                .max(Date::compareTo)
                .orElse(null);
        boolean isNewTs = checkVodIsNew(streamerConfig, date);
        if (!isNewTs) {
            return null;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(date);
        return new MinioRecorder(genRegPathByRegDate(date), date, dirPath + "/" + timeV + "/");
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.MINIO;
    }
}
