package com.sh.engine.processor.plugin.lol;

/**
 * LOL 精彩片段处理过程中共享的固定参数。
 */
final class LolHighlightConstants {
    static final int SNAP_INTERVAL_SECONDS = 4;
    static final int OCR_BATCH_SIZE = 5;
    static final int TOP_INTERVAL_LIMIT = 10;
    static final float MIN_HIGHLIGHT_SCORE = 5.0f;

    static final int ADAPTIVE_COARSE_INTERVAL_SECONDS = 8 * 60;
    static final int ADAPTIVE_MIN_INTERVAL_SECONDS = SNAP_INTERVAL_SECONDS;
    static final int SNAPSHOT_PARALLELISM = 2;
    static final int SEEK_RETRY_OFFSET_SECONDS = 1;
    static final int SEEK_PREROLL_SECONDS = 5;
    static final int MAX_ADAPTIVE_SNAPSHOTS = 200;

    static final String KDA_TEST_CROP_EXPRESSION = "crop=in_w/2:100:in_w/2:0";
    static final String DEFAULT_KDA_CROP_EXPRESSION = "crop=80:30:in_w*867/1000:0";
    static final String KILL_DETAIL_CROP_EXPRESSION = "crop=270:290:in_w*86/100:in_h*3/16";

    static final String KDA_SNAPSHOT_DIR = "kda-snapshot";
    static final String ADAPTIVE_KDA_SNAPSHOT_DIR = "kda-adaptive-snapshot";
    static final String KDA_TEST_SNAPSHOT_DIR = "kda-test-snapshot";
    static final String DETAIL_SNAPSHOT_DIR = "detail-snapshot";

    private LolHighlightConstants() {
    }
}
