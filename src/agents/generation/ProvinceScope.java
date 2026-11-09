package agents.generation;

import contracts.OrderForm;
import domain.Province;
import game.GameState;
import game.GameState_Movement;
import phase.UnitId;

import java.util.*;

/**
 * Fixed movement boundary for a tactical experiment.
 *
 * <p>Membership uses canonical provinces; retained orders keep their exact
 * coast designations. All of a nation's current units must be inside the
 * boundary. The board, ownership, and adjudication rules remain unchanged.</p>
 *
 * <p>This is an optional fixture restriction. Diplodocus uses the full board
 * and does not prescribe a geographic action boundary.</p>
 */
public final class ProvinceScope {

    private final Set<Province> provinces;

    public ProvinceScope(Collection<Province> provinces) {
        Objects.requireNonNull(provinces, "provinces");
        Set<Province> copy = EnumSet.noneOf(Province.class);
        for (Province province : provinces)
            copy.add(Province.canonical(Objects.requireNonNull(province, "province")));
        if (copy.isEmpty())
            throw new IllegalArgumentException("A province scope must not be empty");
        this.provinces = Collections.unmodifiableSet(copy);
    }

    public Set<Province> provinces() {
        return provinces;
    }

    public boolean contains(Province province) {
        return provinces.contains(Province.canonical(Objects.requireNonNull(province, "province")));
    }

    /**
     * Filters movement options before complete actions are constructed.
     * Holds survive; moves, supports, and convoys must keep both references
     * inside the scope. A unit outside the scope is an error, even if it holds.
     */
    public LegalOrderCatalog restrict(GameState state, LegalOrderCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog").requireMatches(state, catalog.power());
        if (!(state instanceof GameState_Movement))
            throw new IllegalArgumentException("Province scoping requires a movement state");

        Map<UnitId, List<OrderForm>> options = new LinkedHashMap<>();
        for (var entry : catalog.unitLocations().entrySet()) {
            if (!contains(entry.getValue()))
                throw new IllegalArgumentException("Unit is outside the province scope: " + entry.getKey()
                        + " at " + entry.getValue());
            options.put(entry.getKey(), catalog.optionsFor(entry.getKey()).stream()
                    .filter(this::allows).toList());
        }
        return new LegalOrderCatalog(state, catalog.power(), options);
    }

    /**
     * Checks a caller-supplied movement action, including completeness.
     * Use this for explicit pools and diagnostic probes which bypass generation.
     */
    public void requireWithin(GameState state, ActionCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        LegalOrderCatalog catalog = restrict(state,
                new LegalOrderGenerator().generate(state, candidate.power()));
        List<ActionValidationError> errors = new CatalogActionValidator().validate(state, candidate, catalog);
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Action is outside the scoped movement catalog: " + errors);
    }

    private boolean allows(OrderForm order) {
        return (order.target() == null || contains(order.target()))
                && (order.auxiliaryTarget() == null || contains(order.auxiliaryTarget()));
    }

}