package kairon.semantics;

import java.util.List;
import java.util.Objects;

/**
 * A typed, immutable, deterministic semantic value.
 *
 * <p>Deliberately closed. Downstream consumers switch exhaustively instead of
 * inspecting an untyped {@code Object}, so a new value shape cannot enter the
 * model silently.</p>
 *
 * <p>Every variant below is required by a mechanism verified in the repository;
 * none is speculative.</p>
 */
public sealed interface SemanticValue {

    /** Whether this value carries a known fact. */
    boolean known();

    /**
     * Absence of knowledge.
     *
     * <p>Distinct from a known empty or zero value. Never guessed away.</p>
     */
    record UnknownValue() implements SemanticValue {

        private static final UnknownValue INSTANCE = new UnknownValue();

        @Override
        public boolean known() {
            return false;
        }
    }

    /** Free text such as a body name, ship name, or message body. */
    record TextValue(String value) implements SemanticValue {

        public TextValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    /** A yes/no fact such as {@code landable} or {@code wasDiscovered}. */
    record BooleanValue(boolean value) implements SemanticValue {

        @Override
        public boolean known() {
            return true;
        }
    }

    /** A whole number such as a signal count or a reward. */
    record IntegralValue(long value) implements SemanticValue {

        @Override
        public boolean known() {
            return true;
        }
    }

    /** A real number such as a distance in light seconds. */
    record DecimalValue(double value) implements SemanticValue {

        public DecimalValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "decimal value must be finite"
                );
            }
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    /**
     * A closed symbolic token such as a flight mode or a vehicle kind.
     *
     * <p>Carries the symbol only. It is not free text and must not be parsed
     * back into prose.</p>
     */
    record SymbolicValue(String symbol) implements SemanticValue {

        public SymbolicValue {
            symbol = requireNonBlank(symbol, "symbol");
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    /**
     * A stable identifier together with what kind of thing it identifies.
     *
     * <p>The kind is mandatory so that an unattributed number can never be
     * silently treated as an identifier for the wrong entity.</p>
     */
    record IdentityValue(String kind, String value)
            implements SemanticValue {

        public IdentityValue {
            kind = requireNonBlank(kind, "kind");
            value = requireNonBlank(value, "value");
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    /** A measured amount with an explicit unit. Never rendered as prose. */
    record QuantityValue(double amount, String unit)
            implements SemanticValue {

        public QuantityValue {
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException(
                        "quantity amount must be finite"
                );
            }
            unit = requireNonBlank(unit, "unit");
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    /** A surface position. Required by codex and touchdown mechanisms. */
    record CoordinatesValue(double latitude, double longitude)
            implements SemanticValue {

        public CoordinatesValue {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                throw new IllegalArgumentException(
                        "coordinates must be finite"
                );
            }
        }

        @Override
        public boolean known() {
            return true;
        }
    }

    static SemanticValue unknown() {
        return UnknownValue.INSTANCE;
    }

    static SemanticValue ofText(String value) {
        return value == null || value.isBlank()
                ? unknown()
                : new TextValue(value);
    }

    static SemanticValue ofBoolean(Boolean value) {
        return value == null ? unknown() : new BooleanValue(value);
    }

    static SemanticValue ofIntegral(Long value) {
        return value == null ? unknown() : new IntegralValue(value);
    }

    static SemanticValue ofIntegral(Integer value) {
        return value == null ? unknown() : new IntegralValue(value);
    }

    static SemanticValue ofDecimal(Double value) {
        return value == null || !Double.isFinite(value)
                ? unknown()
                : new DecimalValue(value);
    }

    static SemanticValue ofSymbol(String symbol) {
        return symbol == null || symbol.isBlank()
                ? unknown()
                : new SymbolicValue(symbol);
    }

    static SemanticValue ofIdentity(String kind, String value) {
        return kind == null || kind.isBlank()
                || value == null || value.isBlank()
                ? unknown()
                : new IdentityValue(kind, value);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
