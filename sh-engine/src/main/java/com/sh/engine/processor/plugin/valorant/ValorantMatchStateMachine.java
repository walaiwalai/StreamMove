package com.sh.engine.processor.plugin.valorant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 根据比分单调性识别完整比赛：0:0 且存在时间时开始，赛点后持续缺失比分时结束。
 */
@Component
@Slf4j
public class ValorantMatchStateMachine {
    static final int MISSING_CONFIRMATION_COUNT = 3;
    static final int END_CONFIRMATION_SECONDS = 60;
    static final int TERMINAL_SCORE_THRESHOLD = 12;
    static final int MINIMUM_SECONDS_PER_SCORE = 25;
    static final int MINIMUM_MATCH_SECONDS = 5 * 60;

    public List<DetectedRange> detect(List<ValorantScoreObservation> sourceObservations) {
        List<ValorantScoreObservation> observations = new ArrayList<>(sourceObservations);
        observations.sort(Comparator.comparingDouble(
                ValorantScoreObservation::getGlobalSecond));

        List<DetectedRange> ranges = new ArrayList<>();
        ActiveMatch active = null;
        for (ValorantScoreObservation observation : observations) {
            if (active == null) {
                if (observation.isOpeningScore()) {
                    active = ActiveMatch.start(observation);
                    log.info("valorant match start detected, second: {}",
                            observation.getGlobalSecond());
                }
                continue;
            }

            if (observation.isOpeningScore()
                    && (active.leftScore > 0 || active.rightScore > 0)
                    && observation.getGlobalSecond() - active.startSecond
                    >= MINIMUM_MATCH_SECONDS) {
                addIfComplete(ranges, active);
                active = ActiveMatch.start(observation);
                log.info("valorant next match start detected, second: {}",
                        observation.getGlobalSecond());
                continue;
            }

            if (!observation.hasCompleteScore()) {
                if (observation.getLeftScore() != null
                        || observation.getRightScore() != null) {
                    active.resetMissing();
                    active.lastValidSecond = observation.getGlobalSecond();
                    log.info("valorant partial score keeps match active, second: {}, "
                                    + "left: {}, time: {}, right: {}",
                            observation.getGlobalSecond(), observation.getLeftScore(),
                            observation.getRoundTime(), observation.getRightScore());
                    continue;
                }
                active.recordMissing(observation.getGlobalSecond());
                log.info("valorant score missing, second: {}, consecutive: {}/{}",
                        observation.getGlobalSecond(), active.missingCount,
                        MISSING_CONFIRMATION_COUNT);
                if (active.canConfirmEnd(observation.getGlobalSecond())) {
                    addIfComplete(ranges, active);
                    active = null;
                }
                continue;
            }

            if (!isPlausibleProgress(active, observation)) {
                log.info("ignore valorant replay or OCR score jump, second: {}, "
                                + "previous: {}-{}, current: {}-{}",
                        observation.getGlobalSecond(), active.leftScore, active.rightScore,
                        observation.getLeftScore(), observation.getRightScore());
                continue;
            }

            active.resetMissing();
            int scoreDelta = observation.getLeftScore() - active.leftScore
                    + observation.getRightScore() - active.rightScore;
            active.leftScore = observation.getLeftScore();
            active.rightScore = observation.getRightScore();
            active.lastValidSecond = observation.getGlobalSecond();
            if (scoreDelta > 0) {
                active.lastScoreChangeSecond = observation.getGlobalSecond();
                log.info("valorant score progress accepted, second: {}, score: {}-{}",
                        observation.getGlobalSecond(), active.leftScore, active.rightScore);
            }
        }
        return ranges;
    }

    private boolean isPlausibleProgress(ActiveMatch active,
                                        ValorantScoreObservation observation) {
        int leftDelta = observation.getLeftScore() - active.leftScore;
        int rightDelta = observation.getRightScore() - active.rightScore;
        if (leftDelta < 0 || rightDelta < 0) {
            return false;
        }
        int totalDelta = leftDelta + rightDelta;
        if (totalDelta == 0) {
            return true;
        }
        double elapsed = observation.getGlobalSecond() - active.lastScoreChangeSecond;
        return elapsed >= totalDelta * MINIMUM_SECONDS_PER_SCORE;
    }

    private void addIfComplete(List<DetectedRange> ranges, ActiveMatch active) {
        double duration = active.lastValidSecond - active.startSecond;
        if (duration < MINIMUM_MATCH_SECONDS || !active.hasTerminalScore()) {
            log.info("skip incomplete valorant score range, start: {}, end: {}, "
                            + "duration: {}, score: {}-{}",
                    active.startSecond, active.lastValidSecond, duration,
                    active.leftScore, active.rightScore);
            return;
        }
        ranges.add(new DetectedRange(active.startSecond, active.lastValidSecond));
        log.info("valorant match end detected, start: {}, end: {}",
                active.startSecond, active.lastValidSecond);
    }

    private static final class ActiveMatch {
        private final double startSecond;
        private double lastValidSecond;
        private double lastScoreChangeSecond;
        private int leftScore;
        private int rightScore;
        private int missingCount;
        private double missingStartedSecond = -1;

        private ActiveMatch(double startSecond) {
            this.startSecond = startSecond;
            this.lastValidSecond = startSecond;
            this.lastScoreChangeSecond = startSecond;
        }

        private static ActiveMatch start(ValorantScoreObservation observation) {
            return new ActiveMatch(observation.getGlobalSecond());
        }

        private void recordMissing(double second) {
            if (missingCount == 0) {
                missingStartedSecond = second;
            }
            missingCount++;
        }

        private void resetMissing() {
            missingCount = 0;
            missingStartedSecond = -1;
        }

        private boolean canConfirmEnd(double second) {
            return missingCount >= MISSING_CONFIRMATION_COUNT
                    && hasTerminalScore()
                    && second - missingStartedSecond >= END_CONFIRMATION_SECONDS;
        }

        private boolean hasTerminalScore() {
            return leftScore >= TERMINAL_SCORE_THRESHOLD
                    || rightScore >= TERMINAL_SCORE_THRESHOLD;
        }
    }

    public static final class DetectedRange {
        private final double startSecond;
        private final double endSecond;

        private DetectedRange(double startSecond, double endSecond) {
            this.startSecond = startSecond;
            this.endSecond = endSecond;
        }

        public double getStartSecond() {
            return startSecond;
        }

        public double getEndSecond() {
            return endSecond;
        }
    }
}
