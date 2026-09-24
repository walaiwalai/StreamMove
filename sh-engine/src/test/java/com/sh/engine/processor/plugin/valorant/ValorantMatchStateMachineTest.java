package com.sh.engine.processor.plugin.valorant;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ValorantMatchStateMachineTest {
    private final ValorantMatchStateMachine stateMachine =
            new ValorantMatchStateMachine();

    @Test
    public void shouldDetectCompleteMatchAfterThreeMissingScores() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(300, 3, null, 1));
        observations.add(observation(700, 13, null, 4));
        addConfirmedMissing(observations, 704);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(100.0, ranges.get(0).getStartSecond(), 0.001);
        assertEquals(700.0, ranges.get(0).getEndSecond(), 0.001);
    }

    @Test
    public void shouldRequireTimerOnlyAtOpeningScore() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, null, 0));
        observations.add(observation(104, 0, "0:19", 0));
        observations.add(observation(700, 13, null, 8));
        addConfirmedMissing(observations, 704);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(104.0, ranges.get(0).getStartSecond(), 0.001);
    }

    @Test
    public void shouldIgnoreReplayAndTooFastScoreGrowth() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(108, 5, null, 0));
        observations.add(observation(200, 1, null, 0));
        observations.add(observation(204, 0, null, 0));
        observations.add(observation(500, 2, null, 0));
        observations.add(observation(800, 12, null, 0));
        addConfirmedMissing(observations, 804);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(800.0, ranges.get(0).getEndSecond(), 0.001);
    }

    @Test
    public void shouldNotEndWhenOneSideScoreIsStillVisible() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(700, 13, null, 4));
        observations.add(observation(704, 13, null, null));
        observations.add(observation(708, null, null, 4));
        observations.add(observation(712, 13, null, null));
        observations.add(observation(716, 13, null, 4));
        addConfirmedMissing(observations, 720);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(716.0, ranges.get(0).getEndSecond(), 0.001);
    }

    @Test
    public void shouldSkipPartialBeginningAndIncompleteTail() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(0, 7, null, 2));
        observations.add(observation(500, 13, null, 4));
        addMissing(observations, 504);
        observations.add(observation(900, 0, "0:23", 0));
        observations.add(observation(1400, 8, null, 6));

        assertEquals(0, stateMachine.detect(observations).size());
    }

    @Test
    public void shouldSkipShortFalseOpening() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(104, 0, null, 0));
        addMissing(observations, 108);

        assertEquals(0, stateMachine.detect(observations).size());
    }

    @Test
    public void shouldKeepMatchActiveAcrossOrdinaryRoundTransition() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(700, 6, null, 4));
        addConfirmedMissing(observations, 704);
        observations.add(observation(900, 7, null, 4));
        observations.add(observation(1200, 12, null, 4));
        addConfirmedMissing(observations, 1204);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(100.0, ranges.get(0).getStartSecond(), 0.001);
        assertEquals(1200.0, ranges.get(0).getEndSecond(), 0.001);
    }

    @Test
    public void shouldNotTreatRecordingTailAsConfirmedMatchEnd() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:23", 0));
        observations.add(observation(1200, 12, null, 9));
        addMissing(observations, 1204);

        assertEquals(0, stateMachine.detect(observations).size());
    }

    @Test
    public void shouldIgnoreFalseOpeningDuringFirstFiveMinutes() {
        List<ValorantScoreObservation> observations = new ArrayList<>();
        observations.add(observation(100, 0, "0:25", 0));
        observations.add(observation(160, 1, "1:03", 0));
        observations.add(observation(164, 0, "0:58", 0));
        observations.add(observation(700, 12, null, 5));
        addConfirmedMissing(observations, 704);

        List<ValorantMatchStateMachine.DetectedRange> ranges =
                stateMachine.detect(observations);

        assertEquals(1, ranges.size());
        assertEquals(100.0, ranges.get(0).getStartSecond(), 0.001);
    }

    private void addMissing(List<ValorantScoreObservation> observations, int startSecond) {
        observations.add(observation(startSecond, null, null, null));
        observations.add(observation(startSecond + 4, null, null, null));
        observations.add(observation(startSecond + 8, null, null, null));
    }

    private void addConfirmedMissing(List<ValorantScoreObservation> observations,
                                     int startSecond) {
        for (int second = startSecond;
             second <= startSecond + ValorantMatchStateMachine.END_CONFIRMATION_SECONDS;
             second += 4) {
            observations.add(observation(second, null, null, null));
        }
    }

    private ValorantScoreObservation observation(double second,
                                                  Integer left,
                                                  String time,
                                                  Integer right) {
        return new ValorantScoreObservation(second, left, time, right);
    }
}
