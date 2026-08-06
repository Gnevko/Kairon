package kairon.system;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What grows on one body, and what has been collected of it.
 *
 * <p>Two halves from two sources that never meet anywhere else. {@code genera}
 * is what a completed surface survey reported — {@code SAASignalsFound} carries
 * a {@code Genuses} array, and it is the only record that names them. Before the
 * survey the game states a biological signal count and no names at all, so an
 * empty map beside a positive count is the ordinary state of a body that has
 * been seen from the system scanner and not yet mapped.</p>
 *
 * <p>{@code completed} is the genera whose sampling sequence finished: three
 * scans, the last of which is {@code ScanOrganic.Analysed}. Kept as raw
 * {@code $Codex_Ent_..._Name;} identifiers, and matched on them — two records
 * differing only in localised label are the same organism, and two differing in
 * identifier are not, whatever their labels say.</p>
 *
 * <p>The label is kept beside the identifier for display, never for comparison,
 * and it may be {@code null}. A genus whose only spelling is the game's own
 * {@code $Codex_Ent_…} symbol has no label, because that symbol is not a word
 * anything may show — and it is still recorded, still counted and still
 * compared. An absent label is a missing word, not a missing organism.</p>
 *
 * <p>Both halves are sorted by identifier. Order carries no meaning, so it is
 * fixed rather than left to a hash — an unordered set would make one recording
 * of one replay differ from the next.</p>
 */
public record BiologicalSurvey(
        Map<String, String> genera,
        Set<String> completed
) {

    /** Nothing surveyed and nothing collected. */
    public static final BiologicalSurvey EMPTY =
            new BiologicalSurvey(Map.of(), Set.of());

    public BiologicalSurvey {
        genera = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(genera, "genera")
        ));
        completed = Collections.unmodifiableSet(new TreeSet<>(
                Objects.requireNonNull(completed, "completed")
        ));
    }

    /**
     * This survey with what a reading named added to it.
     *
     * <p>Additive. A survey does not retract a genus it reported earlier, and a
     * reading that names none says nothing rather than saying there are none —
     * the same rule the signal counts live under.</p>
     */
    public BiologicalSurvey withGenera(Map<String, String> reported) {
        if (reported == null || reported.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new TreeMap<>(genera);
        merged.putAll(reported);
        return new BiologicalSurvey(merged, completed);
    }

    /**
     * This survey with one genus recorded as collected.
     *
     * <p>A completed sequence is recorded whether or not the survey named the
     * genus. Sampling something the scanner never listed is the Commander
     * standing in front of it, and refusing to record that because a survey is
     * missing would make the collection depend on the instrument rather than on
     * what happened.</p>
     */
    public BiologicalSurvey withCompleted(String genusIdentifier) {
        if (genusIdentifier == null
                || genusIdentifier.isBlank()
                || completed.contains(genusIdentifier)) {
            return this;
        }
        Set<String> merged = new TreeSet<>(completed);
        merged.add(genusIdentifier);
        return new BiologicalSurvey(genera, merged);
    }

    /**
     * The surveyed genera with no completed sequence yet.
     *
     * <p>Empty when the body has not been mapped, which is not the same as
     * nothing being left: it is the survey that has not named anything. What is
     * left over is answerable by name only after the mapping, and by count
     * before it.</p>
     */
    public Set<String> remaining() {
        Set<String> outstanding = new TreeSet<>(genera.keySet());
        outstanding.removeAll(completed);
        return Collections.unmodifiableSet(outstanding);
    }

    /** Whether anything at all is recorded here. */
    public boolean isEmpty() {
        return genera.isEmpty() && completed.isEmpty();
    }
}
