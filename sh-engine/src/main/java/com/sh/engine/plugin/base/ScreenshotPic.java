package com.sh.engine.plugin.base;

import lombok.Data;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2024 01 14 16 44
 **/
@Data
public class ScreenshotPic {
    private File picFile;
    private Integer index;

    public ScreenshotPic(File picFile, Integer index) {
        this.picFile = picFile;
        this.index = index;
    }
}
