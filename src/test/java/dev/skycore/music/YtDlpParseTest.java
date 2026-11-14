package dev.skycore.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YtDlpParseTest {
    @Test
    void searchLinesBecomeSongsAndJunkIsDropped() {
        List<MusicTrack> r = YtDlp.parse(List.of(
                "nnoyFnaa_BI\tSEREBRO - Мало тебя\tSEREBRO - Topic\t229",
                "short000001\tClip\tX\t20",
                "mix00000001\tTwo hour mix\tX\t7200",
                "bad\tNot an id\tX\t200",
                "noduration1\tLive stream\tX\tNA",
                "KqJsZ1_1-F0\tМало тебя\tNA\t226.0"));
        assertEquals(2, r.size());
        assertEquals("yt:nnoyFnaa_BI", r.get(0).id);
        assertEquals("SEREBRO", r.get(0).artist);
        assertEquals(229_000, r.get(0).durationMs);
        assertEquals("", r.get(1).artist);
        assertTrue(YtDlp.isYt(r.get(0)));
    }

    @Test
    void badIdsNeverReachTheTool() {
        assertThrows(MusicException.class, () -> YtDlp.streamUrl("yt:--exec evil"));
    }
}
