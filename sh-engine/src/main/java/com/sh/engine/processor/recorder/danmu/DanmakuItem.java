package com.sh.engine.processor.recorder.danmu;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author caiwen
 * @Date 2025 08 03 16 44
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DanmakuItem implements Comparable<DanmakuItem> {
    /**
     * 时间戳
     */
    private Long ts;

    /**
     * 弹幕内容
     */
    private String content;

    // 按照ts排序
    @Override
    public int compareTo(DanmakuItem other) {
        // 处理ts为null的情况，避免空指针异常
        if (this.ts == null) {
            return other.ts == null ? 0 : -1;
        }
        if (other.ts == null) {
            return 1;
        }
        // 按照ts升序排列
        return this.ts.compareTo(other.ts);
    }
}
