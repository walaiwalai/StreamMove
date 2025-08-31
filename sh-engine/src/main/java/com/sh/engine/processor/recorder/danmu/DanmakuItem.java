package com.sh.engine.processor.recorder.danmu;

import com.sh.engine.model.buffer.CsvItem;
import com.sh.engine.util.CsvUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 弹幕信息
 *
 * @Author caiwen
 * @Date 2025 08 03 16 44
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DanmakuItem implements CsvItem, Comparable<DanmakuItem> {
    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 创建者ID
     */
    private String uid;

    /**
     * 创建者
     */
    private String creatorName;

    /**
     * 弹幕内容
     */
    private String content;


    @Override
    public String genHeader() {
        return "timestamp,uid,userName,content";
    }

    @Override
    public String genLine() {
        return timestamp.toString() + ","
                + uid + ","
                + CsvUtil.escapeCsv(creatorName) + ","
                + CsvUtil.escapeCsv(content);
    }

    @Override
    public DanmakuItem covertItem(String line) {
        int length = genHeader().split(",").length;
        String[] parts = line.split(",");
        if (parts.length == length) {
            return new DanmakuItem(Long.valueOf(parts[0]), parts[1], parts[2], parts[3]);
        }
        return null;
    }

    @Override
    public int compareTo(DanmakuItem other) {
        if (other == null) {
            return 0;
        }
        return this.timestamp.compareTo(other.timestamp);
    }
}
