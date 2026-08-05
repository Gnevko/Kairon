package kairon.observation.journal;

/**
 * A journal record whose discriminator this build does not recognise.
 *
 * <p>One wire event can carry several domain events, told apart by a field —
 * {@code ScanType}, {@code JumpType}, a limpet {@code Type}. Frontier adds
 * values to those vocabularies, so "a value we have not researched" is a normal
 * runtime condition rather than a defect, and every split record needs a way to
 * say it.</p>
 *
 * <p>Marking it once, here, is what lets a consumer answer it once. Before the
 * split each such record had its own {@code default} arm inside its own bespoke
 * branch of the behaviour normalizer, and those branches existed only to re-read
 * a discriminator the parser had already seen.</p>
 *
 * <p>An unrecognised variant is still parsed, still carries exact
 * {@code RawJournalData}, and still reaches diagnostics and the GUI. What it
 * must never do is be guessed into one of the researched variants: the whole
 * point of the marker is that Kairon knows it does not know.</p>
 */
public interface UnrecognisedEventVariant extends JournalEventObservation {
}
