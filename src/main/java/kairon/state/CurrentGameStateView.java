package kairon.state;

/**
 * Read-only access to the canonical current game state projection.
 */
public interface CurrentGameStateView {

    CurrentGameStateSnapshot currentSnapshot();
}
