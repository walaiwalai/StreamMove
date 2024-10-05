package com.sh.engine.processor.uploader;

import com.sh.config.manager.ConfigFetcher;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 26
 **/
public abstract class Uploader {
    public abstract String getType();

    /**
     * 初始化上传器
     * 如：用户cookies获取
     */
    public abstract void setUp();

    /**
     * 上传
     *
     * @return
     */
    public abstract boolean upload(String recordPath) throws Exception;

    /**
     * 获取账号保存文件
     * @return  账号文件
     */
    protected File getAccoutFile() {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
        return new File(accountSavePath, UploaderFactory.getAccountFileName(getType()));
    }

    protected String getAccountKey() {
        return getType() + "_" + "cookies";
    }
}
