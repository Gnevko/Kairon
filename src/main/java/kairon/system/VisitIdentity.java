package kairon.system;

/**
 * Who and where canonical state says the Commander is, as this registry needs
 * it.
 *
 * <p>Four plain values rather than the canonical snapshot itself. The registry
 * must not import {@code kairon.state}: canonical body facts are to be read out
 * of the registry rather than kept beside it, and a registry that imports the
 * projection it will feed is the cycle that change would run into.</p>
 *
 * @param commanderFid  the Commander canonical state reports now, or null
 * @param shipId        the ship canonical state reports now, or null
 * @param systemAddress the system canonical state reports now, or null
 * @param systemName    the system's name, or null when it is not established
 */
public record VisitIdentity(
        String commanderFid,
        Long shipId,
        Long systemAddress,
        String systemName
) {
}
