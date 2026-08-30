package com.sh.engine.processor.plugin.highlight.valorant;

import org.junit.Assert;
import org.junit.Test;

public class ValorantEventDeduplicatorTest {
    @Test
    public void shouldDeduplicatePersistentKillFeedRow() {
        ValorantEventDeduplicator deduplicator = new ValorantEventDeduplicator();

        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 10, "Me Vandal Sova", 0x0FL));
        Assert.assertTrue(deduplicator.isDuplicate(
                "SELF_KILL", 12, "Me Vandal Sova", 0x0FL));
        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 16, "Me Vandal Sova", 0x0FL));
    }

    @Test
    public void shouldNotDeduplicateKillAndDeath() {
        ValorantEventDeduplicator deduplicator = new ValorantEventDeduplicator();

        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 10, "Me Vandal Sova", 0x0FL));
        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_DEATH", 11, "Sova Vandal Me", 0x0FL));
    }

    @Test
    public void shouldTolerateSmallOcrTextChanges() {
        ValorantEventDeduplicator deduplicator = new ValorantEventDeduplicator();

        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 10, "Me Vandal Sova", 0L));
        Assert.assertTrue(deduplicator.isDuplicate(
                "SELF_KILL", 12, "Me Vandal S0va", -1L));
        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 13, "Jett Operator Reyna", -1L));
    }

    @Test
    public void shouldUseImageHashWhenOcrTextIsDifferent() {
        ValorantEventDeduplicator deduplicator = new ValorantEventDeduplicator();

        Assert.assertFalse(deduplicator.isDuplicate(
                "SELF_KILL", 10, "unreadable-one", 0x0FL));
        Assert.assertTrue(deduplicator.isDuplicate(
                "SELF_KILL", 12, "completely-different", 0x0FL));
    }
}
