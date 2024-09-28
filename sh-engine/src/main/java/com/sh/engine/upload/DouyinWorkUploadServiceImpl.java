//package com.sh.engine.upload;
//
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.model.video.LocalVideo;
//import com.sh.config.utils.EnvUtil;
//import com.sh.engine.UploadPlatformEnum;
//import com.sh.engine.model.upload.BaseUploadTask;
//import com.sh.engine.playwright.DouyinPlaywright;
//import com.sh.engine.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
///**
// * @Author caiwen
// * @Date 2024 05 19 19 07
// **/
//@Slf4j
//@Component
//public class DouyinWorkUploadServiceImpl extends AbstractWorkUploadService {
//    @Autowired
//    private MsgSendService msgSendService;
//
//    @Override
//    public String getName() {
//        return UploadPlatformEnum.DOU_YIN.getType();
//    }
//
//    @Override
//    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
//        if (!DouyinPlaywright.isCkValid()) {
//            if (EnvUtil.isProd()) {
//                // 目前直接就返回通过
//                return true;
//            } else {
//                DouyinPlaywright.genCookies();
//            }
//        }
//
//        for (LocalVideo localVideo : localVideos) {
//            if (!StringUtils.equals("highlight", localVideo.getTitle())) {
//                continue;
//            }
//
//            // 只上传精彩片段
//            boolean success = DouyinPlaywright.upload(localVideo.getLocalFileFullPath(), task.getTitle(), task.getTags());
//
//            if (success) {
//                msgSendService.send(localVideo.getLocalFileFullPath() + "路径下的视频上传抖音成功！");
//            } else {
//                msgSendService.send(localVideo.getLocalFileFullPath() + "路径下的视频上传抖音失败！");
//                throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//            }
//        }
//
//        return true;
//    }
//}
