package agents.encoding;

import agents.generation.*;
import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Movement inputs and complete actions in a common training/inference order.
 * Locations identify units in the encoding; persistent UnitIds stay in Java.
 */
public final class MovementEncoding {

    public static final String SCHEMA = "jreferee-movement-v2";

    private final GameState_Movement state;
    private final Nation power;
    private final LegalOrderCatalog catalog;
    private final List<UnitId> units;
    private final List<List<OrderForm>> options;

    public MovementEncoding(GameState_Movement state, Nation power) {
        this.state = Objects.requireNonNull(state, "state");
        this.power = Objects.requireNonNull(power, "power");
        catalog = new LegalOrderGenerator().generate(state, power);
        units = catalog.unitLocations().keySet().stream()
                .sorted(Comparator.comparing(unit -> state.board().locationOf(unit).name()))
                .toList();
        options = units.stream().map(unit -> catalog.optionsFor(unit).stream()
                .sorted(Comparator.comparing(this::token)).toList()).toList();
    }

    public LegalOrderCatalog catalog() {
        return catalog;
    }

    /**
     * One immutable list per issuing unit, ordered by current location name.
     * Each list includes every option in the full legal catalog.
     */
    public List<List<OrderForm>> options() {
        return options;
    }

    /**
     * Supplies the same public inputs and masks directly to a live policy.
     */
    public MovementInput input() {
        List<MovementInput.Unit> boardUnits = state.board().locations().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().name()))
                .map(entry -> new MovementInput.Unit(entry.getKey().owner(),
                        entry.getKey().unitType(), entry.getValue())).toList();
        List<MovementInput.Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < units.size(); slot++) {
            Province location = state.board().locationOf(units.get(slot));
            slots.add(new MovementInput.Slot(location, options.get(slot).stream()
                    .map(order -> MovementInput.Option.from(order, location)).toList()));
        }
        return new MovementInput(state.moment(), power, boardUnits, state.board().owners(), slots);
    }

    /**
     * Returns one local option index per unit. Submission order is immaterial.
     * Missing, duplicate, foreign, or unavailable orders are rejected.
     */
    public List<Integer> encode(Collection<? extends OrderForm> orders) {
        ActionCandidate candidate = new ActionCandidate(power, orders, 0, ActionCandidate.Source.POLICY);
        var errors = new CatalogActionValidator().validate(state, candidate, catalog);
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Action cannot be encoded: " + errors);

        List<Integer> action = new ArrayList<>();
        for (List<OrderForm> choices : options) {
            OrderForm selected = candidate.orders().stream().filter(choices::contains)
                    .findFirst().orElseThrow();
            action.add(choices.indexOf(selected));
        }
        return List.copyOf(action);
    }

    /**
     * Resolves local option indices to the current state's immutable orders.
     */
    public List<OrderForm> decode(List<Integer> action) {
        Objects.requireNonNull(action, "action");
        if (action.size() != options.size())
            throw new IllegalArgumentException("One option index is required per unit");
        List<OrderForm> orders = new ArrayList<>();
        for (int slot = 0; slot < options.size(); slot++) {
            Integer index = action.get(slot);
            if (index == null || index < 0 || index >= options.get(slot).size())
                throw new IllegalArgumentException("Invalid option index at slot " + slot);
            orders.add(options.get(slot).get(index));
        }
        return List.copyOf(orders);
    }

    /**
     * Stable order label: type, current location, order type, target, auxiliary.
     * A dash represents an absent target. Support references use provinces;
     * issuing locations and Fleet move destinations retain their coasts.
     */
    public String token(OrderForm form) {
        if (!(Objects.requireNonNull(form, "form") instanceof Order order)
                || order.getClass() != Order.class)
            throw new IllegalArgumentException("Expected an immutable movement order");
        Province location = state.board().locationOf(order.unit());
        if (location == null)
            throw new IllegalArgumentException("Order unit is absent from the position");
        return order.unitType().name() + " " + location.name() + " " + order.orderType().name()
                + " " + name(order.orderType() == OrderType.SUPPORT
                ? Province.canonical(order.target()) : order.target())
                + " " + name(order.orderType() == OrderType.SUPPORT
                ? Province.canonical(order.auxiliaryTarget()) : order.auxiliaryTarget());
    }

    /**
     * Serializes public movement inputs and masks, without submitted orders,
     * historical results, source metadata, or value targets.
     */
    public String toJson() {
        StringBuilder out = new StringBuilder("{\"schema\":");
        appendString(out, SCHEMA);
        out.append(",\"map\":\"STANDARD\",\"year\":").append(state.moment().year());
        out.append(",\"phase\":");
        appendString(out, state.moment().gamePhase().name());
        out.append(",\"power\":");
        appendString(out, power.name());
        out.append(",\"units\":[");
        var boardUnits = state.board().locations().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().name())).toList();
        for (int index = 0; index < boardUnits.size(); index++) {
            if (index > 0) out.append(',');
            var entry = boardUnits.get(index);
            strings(out, List.of(entry.getKey().owner().name(),
                    entry.getKey().unitType().name(), entry.getValue().name()));
        }
        out.append("],\"centers\":[");
        var centers = state.board().owners().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name())).toList();
        for (int index = 0; index < centers.size(); index++) {
            if (index > 0) out.append(',');
            var entry = centers.get(index);
            strings(out, List.of(entry.getKey().name(), entry.getValue().name()));
        }
        out.append("],\"slots\":[");
        for (int slot = 0; slot < units.size(); slot++) {
            if (slot > 0) out.append(',');
            out.append("{\"location\":");
            appendString(out, state.board().locationOf(units.get(slot)).name());
            out.append(",\"options\":");
            strings(out, options.get(slot).stream().map(this::token).toList());
            out.append('}');
        }
        return out.append("]}").toString();
    }

    private static String name(Province province) {
        return province == null ? "-" : province.name();
    }

    private static void strings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(',');
            appendString(out, values.get(index));
        }
        out.append(']');
    }
}