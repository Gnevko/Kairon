package kairon.state;

/**
 * Where the Commander physically is.
 *
 * <p>{@code SRV} is a conventional Surface Recon Vehicle and {@code SLV} a
 * Ship-Launched Vessel such as the Nomad. The journal boards both with the same
 * {@code SRV=true} flag, so which of the two this is comes from the vehicle the
 * runtime identity resolves to, never from the flag alone.</p>
 */
public enum CommanderLocationMode {
    SHIP,
    SRV,
    SLV,
    ON_FOOT,
    UNKNOWN
}
