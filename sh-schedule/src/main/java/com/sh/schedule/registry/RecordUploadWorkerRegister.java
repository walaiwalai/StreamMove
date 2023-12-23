//package com.sh.schedule.registry;
//
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.schedule.worker.ProcessWorker;
//
///**
// * @author caiWen
// * @date 2023/2/1 23:07
// */
//public class RecordUploadWorkerRegister extends ProcessWorkerRegister {
//    @Override
//    public Class<? extends ProcessWorker> getWorker() {
//        return RecordUploadWorker.class;
//    }
//
//    @Override
//    protected boolean needRegistry() {
//        return true;
//    }
//
//    @Override
//    public String getCronExpr() {
//        // 默认每10分钟检查一次
//        return ConfigFetcher.getInitConfig().getRecordUploadCron();
//    }
//
//    @Override
//    protected String getPrefix() {
//        return "RECORD_UPLOAD";
//    }
//}
