package com.sh.engine.model.danmaku;

import lombok.Data;

/** 单张或相邻送审帧中可直接观察到的事实。 */
@Data
public class VisualObservation {
    /** 从 1 开始的送审图片序号。 */
    private Integer frameIndex;
    /** 与图片序号对应的源视频时间，格式 HH:mm:ss。 */
    private String timestamp;
    /** 只描述画面直接可见的主体、场景、动作或界面状态。 */
    private String observableFacts;
    /** 与上一张图片相比直接可见的变化；不能确认时为空。 */
    private String visibleChange;
    /** high、medium 或 low。 */
    private String certainty;
    /** 是否适合作为清晰且与事件相关的封面原始帧。 */
    private Boolean coverCandidate;
}
