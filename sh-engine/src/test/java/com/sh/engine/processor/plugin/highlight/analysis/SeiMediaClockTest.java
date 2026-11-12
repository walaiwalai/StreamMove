package com.sh.engine.processor.plugin.highlight.analysis;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SeiMediaClockTest {
    @Test public void pairsAbsoluteClockWithPtsAndRejectsDiscontinuities() {
        SeiMediaClock.Source source = new SeiMediaClock.Source(new File("P01.mp4"), 20, .022,
                Arrays.asList(new SeiMediaClock.Anchor(.033, 1794031298940L),
                        new SeiMediaClock.Anchor(2.033, 1794031300940L)));
        assertEquals(1.011, source.locate(1794031299940L), .00001);
        assertNull(source.locate(1794031306000L));
        SeiMediaClock.Source discontinuity = new SeiMediaClock.Source(new File("P01.mp4"), 50, 0,
                Arrays.asList(new SeiMediaClock.Anchor(1, 1794031299000L), new SeiMediaClock.Anchor(2, 1794031330000L)));
        assertNull(discontinuity.locate(1794031300000L));
    }

    @Test public void readsSeiJsonFromAvccInsteadOfGuessingFromFilename() throws Exception {
        byte[] json = "{\"ts\":1794031298940}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream nal = new ByteArrayOutputStream();
        nal.write(6); nal.write(100); nal.write(json.length); nal.write(json); nal.write(128);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(packet);
        output.writeInt(nal.size()); output.write(nal.toByteArray());
        assertEquals(Long.valueOf(1794031298940L), SeiMediaClock.timestamp(packet.toByteArray(), 4));
        try { SeiMediaClock.timestamp(new byte[]{0, 0, 1, 0, 6}, 4); fail("malformed NAL accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("NAL")); }
    }

}
