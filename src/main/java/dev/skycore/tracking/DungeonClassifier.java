package dev.skycore.tracking;

import dev.skycore.data.Activity;
import dev.skycore.data.Activity.Classification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exact dungeon floor of a run. Normal floors (F1–F7), Master Mode (M1–M7) and the Entrance are separate keys –
 * "M3" can never become "F3" because the mode is part of the key everywhere.
 * <p>
 * Evidence: the sidebar line "The Catacombs (M3)" seen during the run (0.95) and the end-of-run summary line
 * "Master Mode Catacombs - Floor III" (0.85). Both agreeing → 0.99. Different floors (sidebar showed two floors, or
 * sidebar and summary disagree) → uncertain. No evidence → uncertain; the run is kept but never added to any
 * floor's statistics.
 */
public final class DungeonClassifier {
    private DungeonClassifier() {}

    public static Classification classify(Collection<String> sidebarFloors, String summaryFloor) {
        Classification c = new Classification();
        Set<String> seen = new LinkedHashSet<>();
        if (sidebarFloors != null) for (String f : sidebarFloors) if (Activity.isFloor(f)) seen.add(f);
        boolean summary = Activity.isFloor(summaryFloor);
        for (String f : seen) c.evidence.add("evidence.sidebar|" + f);
        if (summary) c.evidence.add("evidence.run_summary|" + summaryFloor);

        if (seen.size() > 1) {
            c.missing.add("evidence.conflict_floor");
            c.candidates.addAll(seen);
            if (summary && !c.candidates.contains(summaryFloor)) c.candidates.add(summaryFloor);
            return c;
        }
        String side = seen.isEmpty() ? null : seen.iterator().next();
        if (side != null && summary && !side.equals(summaryFloor)) {
            c.missing.add("evidence.conflict_floor");
            c.candidates.add(side);
            c.candidates.add(summaryFloor);
            return c;
        }
        if (side == null && !summary) {
            c.missing.add("evidence.no_floor");
            return c;
        }
        c.key = side != null ? side : summaryFloor;
        c.confidence = side != null && summary ? 0.99 : side != null ? 0.95 : 0.85;
        c.confirmed = c.confidence >= SlayerSession.CONFIRM;
        return c;
    }
}
