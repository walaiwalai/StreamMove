package com.sh.engine.processor.plugin.lol;

/**
 * 表示稀疏扫描无法可靠完成，应回退到原来的密集截图流程。
 */
public class LolAdaptiveScanException extends RuntimeException {
    public LolAdaptiveScanException(String message) {
        super(message);
    }

    public LolAdaptiveScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
