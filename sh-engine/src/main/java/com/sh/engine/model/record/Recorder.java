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
import java.util.List;

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
    private List<TsRecordInfo> tsViews;
}
