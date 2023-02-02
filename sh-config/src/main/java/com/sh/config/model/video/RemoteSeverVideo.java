package com.sh.config.model.video;

/**
 * 已经上传到服务器的视频对象
 * @author caiWen
 * @date 2023/1/26 9:25
 */
public class RemoteSeverVideo {
    /**
     * 上传的视频标题
     */
    private String title;

    /**
     * 上传的视频藐视
     */
    private String desc;

    /**
     * 上传的视频在服务器上的地址
     */
    private String filename;

    public RemoteSeverVideo() {}

    public RemoteSeverVideo(String title, String desc, String filename) {
        this.title = title;
        this.desc = desc;
        this.filename = filename;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }
}
