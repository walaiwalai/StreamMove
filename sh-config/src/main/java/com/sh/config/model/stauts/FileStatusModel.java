package com.sh.config.model.stauts;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.video.UploadVideoPair;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 22:51
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class FileStatusModel {
    private String path;
    private String recorderName;
    private String recorderLink;
    private List<String> tags;
    private Integer tid;
    private Boolean uploadLocalFile;
    private Boolean deleteLocalFile;
    private Boolean isPost;
    private Boolean isFailed;
    private Integer delayTime;
    private String templateTitle;
    private String desc;
    private String source;
    private String dynamic;
    private Integer copyright;
    private String timeV;
    private Date startRecordTime;
    private String endRecordTime;
    private UploadVideoPair videoParts;


//    public FileStatusModel(Recorder recorder) {
//        RecordTask recordTask = recorder.getRecordTask();
//
//        this.path = recorder.getSavePath();
//        this.recorderName = recordTask.getRecorderName();
//        this.recorderLink = recordTask.getStreamerInfo().getRoomUrl();
//        this.tags = recordTask.getStreamerInfo().getTags();
//        this.tid = recordTask.getStreamerInfo().getTid();
//        this.startRecordTime = new Date();
//        this.uploadLocalFile = recordTask.getStreamerInfo().getUploadLocalFile();
//        this.deleteLocalFile = recordTask.getStreamerInfo().getDeleteLocalFile();
//        this.isPost = false;
//        this.isFailed = false;
//        this.delayTime = recordTask.getStreamerInfo().getDelayTime() == null ? 2 :
//                recordTask.getStreamerInfo().getDelayTime();
//        this.templateTitle = recordTask.getStreamerInfo().getTemplateTitle();
//        this.desc = recordTask.getStreamerInfo().getDesc();
//        this.source = recordTask.getStreamerInfo().getSource();
//        this.dynamic = recordTask.getStreamerInfo().getDynamic();
//        this.copyright = recordTask.getStreamerInfo().getCopyright();
//        this.timeV = recordTask.getTimeV();
//    }

    /**
     * 写到fileStatus.json，没有值不覆盖
     *
     * @param dirName
     */
    public void writeSelfToFile(String dirName) {
        File file = new File(dirName, "fileStatus.json");
        if (file.exists()) {
            return;
        }

        // 创建一个新文件，并把自身写到文件
        String statusStr = JSON.toJSONString(this);
        try {
            file.createNewFile();
            IOUtils.write(statusStr, new FileOutputStream(file), "utf-8");
            log.info("Create fileStatus.json: {}", statusStr);
        } catch (IOException e) {
            log.error("create file fail, savePath: {}", dirName, e);
        }
    }


    /**
     * 只进行覆盖操作
     *
     * @param dirName
     * @param updated
     */
    public static void updateToFile(String dirName, FileStatusModel updated) {
        File file = new File(dirName, "fileStatus.json");
        if (!file.exists()) {
            return;
        }

        try {
            String oldStatusStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            JSONObject statusObj = JSON.parseObject(oldStatusStr);
            statusObj.putAll(JSONObject.parseObject(JSON.toJSONString(updated)));
            String finalStatus = statusObj.toJSONString();
            IOUtils.write(finalStatus, new FileOutputStream(file), "utf-8");
            log.info("fileStatus.json updated success, content: {}", finalStatus);
        } catch (Exception e) {
            log.error("update file fail, savePath: {}", dirName, e);
        }
    }

    public static FileStatusModel loadFromFile(String dirName) {
        File statusFile = new File(dirName, "fileStatus.json");
        String statusStr = null;
        try {
            statusStr = IOUtils.toString(new FileInputStream(statusFile), "utf- 8");
        } catch (IOException e) {
            log.error("open fileStatus.json fail, maybe file not exited, dirName: {}", dirName);
        }
        return JSON.parseObject(statusStr, FileStatusModel.class);
    }
}
