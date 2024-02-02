package com.sh.engine.model.record;

import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.util.RecordConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/1/23 14:36
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class Recorder {
    /**
     * 当前录像的时间
     * 如：2023-02-12晚上
     */
    private String timeV;

    /**
     * 录播视频保存路径
     * 如：...download/TheShy/2023-02-12
     */
    private String savePath;

    /**
     * 拉视频流的地址(不是roomUrl)
     */
    private String streamUrl;

    /**
     * 视频切片地址
     */
    private TsRecordInfo tsRecordInfo;

    /**
     * 写当前状态到fileStatus.json文件
     */
    public void writeInfoToFileStatus() {
        File file = new File(savePath, "fileStatus.json");
        FileStatusModel fileStatusModel = RecordConverter.convertToFileStatusModel(this);
        if (!file.exists()) {
            fileStatusModel.writeSelfToFile(savePath);
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            FileStatusModel.updateToFile(savePath,
                    FileStatusModel.builder()
                            .updateTime(formatter.format(new Date()))
                            .build()
            );
        }

    }
}
