package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.HighlightEvidenceReference;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class HighlightClipBoundaryResolverTest {

    @Test
    public void shouldUseImmediateSetupAndIgnoreOffVideoDanmakuForClipBounds() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 5),
                item("VISION-002", "VISION", 30),
                item("VISION-003", "VISION", 35),
                item("VISION-004", "VISION", 55),
                item("DANMAKU-001", "DANMAKU", 90));
        HighlightFactVerification facts = facts(
                Arrays.asList(reference("VISION-001"), reference("VISION-002")),
                reference("VISION-003"), reference("VISION-004"),
                reference("DANMAKU-001"));

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 100), catalog,
                new HashSet<>(Arrays.asList(35, 55)));

        Assert.assertTrue(resolution.isAccepted());
        Assert.assertEquals(30, resolution.getClipRange().getStartSecond());
        Assert.assertEquals(58, resolution.getClipRange().getEndSecond());
    }

    @Test
    public void shouldRejectSetupThatOnlyOccursAfterAction() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 40),
                item("VISION-002", "VISION", 30),
                item("VISION-003", "VISION", 50));
        HighlightFactVerification facts = facts(
                Collections.singletonList(reference("VISION-001")),
                reference("VISION-002"), reference("VISION-003"), null);

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 100), catalog,
                Collections.singleton(30));

        Assert.assertFalse(resolution.isAccepted());
        Assert.assertTrue(resolution.getViolations().contains(
                "铺垫证据没有发生在关键动作之前"));
    }

    @Test
    public void shouldRejectSetupThatIsTooFarFromTheKeyAction() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 10),
                item("VISION-002", "VISION", 35),
                item("VISION-003", "VISION", 40));
        HighlightFactVerification facts = facts(
                Collections.singletonList(reference("VISION-001")),
                reference("VISION-002"), reference("VISION-003"), null);

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 80), catalog,
                Collections.singleton(35));

        Assert.assertFalse(resolution.isAccepted());
        Assert.assertTrue(resolution.getViolations().contains(
                "最近的必要铺垫距离关键动作超过18秒"));
    }

    @Test
    public void shouldFallbackToVerifiedEventFrameWhenNoPreferredCoverExists() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 20),
                item("VISION-002", "VISION", 35),
                item("VISION-003", "VISION", 50));
        HighlightFactVerification facts = facts(
                Collections.singletonList(reference("VISION-001")),
                reference("VISION-002"), reference("VISION-003"), null);

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 80), catalog,
                Collections.emptySet());

        Assert.assertTrue(resolution.isAccepted());
        Assert.assertEquals(Integer.valueOf(35), resolution.getCoverTimestamp());
    }

    @Test
    public void shouldNotPadCompleteShortEventToTwentySeconds() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 30),
                item("VISION-002", "VISION", 32),
                item("VISION-003", "VISION", 38));
        HighlightFactVerification facts = facts(
                Collections.singletonList(reference("VISION-001")),
                reference("VISION-002"), reference("VISION-003"), null);

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 80), catalog,
                Collections.singleton(32));

        Assert.assertTrue(resolution.isAccepted());
        Assert.assertEquals(30, resolution.getClipRange().getStartSecond());
        Assert.assertEquals(42, resolution.getClipRange().getEndSecond());
    }

    @Test
    public void shouldPadAfterOutcomeBeforeAddingUnverifiedOpeningFrames() {
        HighlightEvidenceCatalog catalog = catalog(
                item("VISION-001", "VISION", 30),
                item("VISION-002", "VISION", 31),
                item("VISION-003", "VISION", 34));
        HighlightFactVerification facts = facts(
                Collections.singletonList(reference("VISION-001")),
                reference("VISION-002"), reference("VISION-003"), null);

        HighlightClipBoundaryResolver.Resolution resolution = resolver().resolve(
                facts, new HighlightClipRange(0, 80), catalog,
                Collections.singleton(31));

        Assert.assertTrue(resolution.isAccepted());
        Assert.assertEquals(30, resolution.getClipRange().getStartSecond());
        Assert.assertEquals(42, resolution.getClipRange().getEndSecond());
        Assert.assertEquals(Integer.valueOf(34),
                resolution.getEventAnchorTimestamp());
    }

    private HighlightClipBoundaryResolver resolver() {
        return new HighlightClipBoundaryResolver();
    }

    private HighlightEvidenceCatalog catalog(HighlightEvidenceItem... items) {
        return new HighlightEvidenceCatalog(
                Collections.emptyList(), Collections.emptyList(), null,
                Arrays.asList(items));
    }

    private HighlightEvidenceItem item(String id, String source, int second) {
        return new HighlightEvidenceItem(id, source, second, second, "直接可见事实");
    }

    private HighlightFactVerification facts(
            java.util.List<HighlightEvidenceReference> setup,
            HighlightEvidenceReference action,
            HighlightEvidenceReference outcome,
            HighlightEvidenceReference reaction) {
        HighlightFactVerification facts = new HighlightFactVerification();
        facts.setSetupEvidence(setup);
        facts.setActionEvidence(Collections.singletonList(action));
        facts.setOutcomeEvidence(Collections.singletonList(outcome));
        facts.setReactionEvidence(reaction == null
                ? Collections.emptyList() : Collections.singletonList(reaction));
        facts.setCoverTimestamp("00:00:35");
        return facts;
    }

    private HighlightEvidenceReference reference(String evidenceId) {
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        reference.setEvidenceId(evidenceId);
        return reference;
    }
}
