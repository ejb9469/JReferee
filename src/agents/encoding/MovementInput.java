package agents.encoding;

import contracts.OrderForm;
import contracts.OrderForms;
import domain.*;
import game.GameMoment;

import java.util.*;

// NOTE: The imports are light, this is a particularly performance-sensitive package.

/**
 * Immutable public position & stored masks.<br><br>
 *
 * Public movement position and ordered legal masks shared by training and play.
 * Unit identities, source metadata, submitted actions, and outcomes are absent.
 */
public final class MovementInput {

    public record Unit(Nation owner, UnitType type, Province location) {
        public Unit {
            Objects.requireNonNull(owner, "owner");
            if (!OrderForms.isValidUnitLocation(type, location))
                throw new IllegalArgumentException("Invalid unit location: " + type + " " + location);
        }
    }

    public record Slot(Province location, List<Option> options) {
        public Slot {
            Objects.requireNonNull(location, "location");
            options = List.copyOf(options);
            if (options.isEmpty())
                throw new IllegalArgumentException("A movement slot needs legal options");
            String previous = "";
            boolean hold = false;
            for (Option option : options) {
                if (option.origin() != location || previous.compareTo(option.label()) >= 0)
                    throw new IllegalArgumentException("Options must have one issuer and unique sorted labels");
                previous = option.label();
                hold |= option.type() == OrderType.HOLD;
            }
            if (!hold)
                throw new IllegalArgumentException("A movement slot must contain HOLD");
        }
    }

    /**
     * Parsed movement-v2 label. Instances can be reused across positions.
     */
    public static final class Option {
        private final UnitType unitType;
        private final Province origin, target, auxiliary;
        private final OrderType type;
        private final String label;
        private final List<Province> destinations;

        private Option(UnitType unitType, Province origin, OrderType type,
                       Province target, Province auxiliary) {
            this.unitType = Objects.requireNonNull(unitType, "unitType");
            this.origin = Objects.requireNonNull(origin, "origin");
            this.type = Objects.requireNonNull(type, "type");
            this.target = target;
            this.auxiliary = auxiliary;
            boolean legal = OrderForms.isValidUnitLocation(unitType, origin) && switch (type) {
                case HOLD -> target == null && auxiliary == null;
                case MOVE -> auxiliary == null && OrderForms.isPotentialMove(unitType, origin, target);
                case SUPPORT -> target != null && target == Province.canonical(target)
                        && auxiliary == Province.canonical(auxiliary) && target != auxiliary
                        && OrderForms.canSupportTo(unitType, origin, auxiliary == null ? target : auxiliary);
                case CONVOY -> unitType == UnitType.FLEET && origin.geography == Geography.WATER
                        && OrderForms.isPotentialConvoyMove(target, auxiliary);
                default -> false;
            };
            if (!legal)
                throw new IllegalArgumentException("Invalid movement option");
            label = unitType.name() + " " + origin.name() + " " + type.name()
                    + " " + name(target) + " " + name(auxiliary);
            if (type == OrderType.SUPPORT && auxiliary != null) {
                List<Province> possible = new ArrayList<>();
                possible.add(auxiliary);
                for (Province province : Province.values())
                    if (province.parent == auxiliary) possible.add(province);
                destinations = List.copyOf(possible);
            } else destinations = List.of();
        }

        public static Option parse(String label) {
            String[] fields = Objects.requireNonNull(label, "label").split(" ", -1);
            if (fields.length != 5)
                throw new IllegalArgumentException("Expected a five-field movement label");
            Option option = new Option(UnitType.valueOf(fields[0]), Province.valueOf(fields[1]),
                    OrderType.valueOf(fields[2]), province(fields[3]), province(fields[4]));
            if (!option.label.equals(label))
                throw new IllegalArgumentException("Noncanonical movement label: " + label);
            return option;
        }

        public static Option from(OrderForm order, Province origin) {
            boolean support = order.orderType() == OrderType.SUPPORT;
            return new Option(order.unitType(), origin, order.orderType(),
                    support ? Province.canonical(order.target()) : order.target(),
                    support ? Province.canonical(order.auxiliaryTarget()) : order.auxiliaryTarget());
        }

        public UnitType unitType() { return unitType; }
        public Province origin() { return origin; }
        public OrderType type() { return type; }
        public Province target() { return target; }
        public Province auxiliary() { return auxiliary; }
        public String label() { return label; }

        public Province destination() {
            return switch (type) {
                case HOLD -> origin;
                case MOVE -> target;
                case SUPPORT -> auxiliary == null ? target : auxiliary;
                case CONVOY -> auxiliary;
                default -> throw new IllegalStateException("Unsupported movement type");
            };
        }

        private static Province province(String value) {
            return value.equals("-") ? null : Province.valueOf(value);
        }

        private static String name(Province province) {
            return province == null ? "-" : province.name();
        }
    }

    private final GameMoment moment;
    private final Nation power;
    private final List<Unit> units;
    private final Map<Province, Nation> centers;
    private final List<Slot> slots;
    private final Unit[] occupants = new Unit[Province.values().length];

    public MovementInput(GameMoment moment, Nation power, List<Unit> units,
                         Map<Province, Nation> centers, List<Slot> slots) {
        this.moment = Objects.requireNonNull(moment, "moment");
        this.power = Objects.requireNonNull(power, "power");
        if (moment.year() < 1 || !moment.gamePhase().isMovement())
            throw new IllegalArgumentException("Expected a positive year and movement phase");
        this.units = List.copyOf(units);
        this.slots = List.copyOf(slots);
        EnumMap<Province, Nation> owned = new EnumMap<>(Province.class);
        centers.forEach((location, owner) -> {
            if (location != Province.canonical(location) || !location.supplyCenter)
                throw new IllegalArgumentException("Expected a canonical supply center");
            owned.put(location, Objects.requireNonNull(owner, "center owner"));
        });
        this.centers = Collections.unmodifiableMap(owned);
        String previous = "";
        int ownUnits = 0;
        for (Unit unit : this.units) {
            int province = Province.canonical(unit.location()).ordinal();
            if (previous.compareTo(unit.location().name()) >= 0 || occupants[province] != null)
                throw new IllegalArgumentException("Units must have unique provinces and sorted locations");
            previous = unit.location().name();
            occupants[province] = unit;
            if (unit.owner() == power) ownUnits++;
        }
        if (this.slots.size() != ownUnits)
            throw new IllegalArgumentException("One slot is required for every unit of the deciding power");
        previous = "";
        for (Slot slot : this.slots) {
            Unit issuer = unitAt(slot.location());
            if (issuer == null || issuer.owner() != power || issuer.location() != slot.location()
                    || previous.compareTo(slot.location().name()) >= 0)
                throw new IllegalArgumentException("Invalid issuing slot: " + slot.location());
            previous = slot.location().name();
            for (Option option : slot.options()) {
                if (option.unitType() != issuer.type())
                    throw new IllegalArgumentException("Option has the wrong issuing unit type");
                if (option.type() == OrderType.SUPPORT || option.type() == OrderType.CONVOY) {
                    Unit referenced = unitAt(option.target());
                    if (referenced == null || referenced == issuer)
                        throw new IllegalArgumentException("Option references an absent unit or its own issuer");
                    if (option.type() == OrderType.CONVOY && referenced.type() != UnitType.ARMY)
                        throw new IllegalArgumentException("Only an Army can be convoyed");
                    if (option.type() == OrderType.SUPPORT && option.auxiliary() != null) {
                        boolean reachable = false;
                        for (Province destination : option.destinations)
                            if (OrderForms.isPotentialMove(referenced.type(), referenced.location(), destination)) {
                                reachable = true;
                                break;
                            }
                        if (!reachable)
                            throw new IllegalArgumentException("Supported unit cannot make the referenced move");
                    }
                }
            }
        }
    }

    public GameMoment moment() { return moment; }
    public Nation power() { return power; }
    public List<Unit> units() { return units; }
    public Map<Province, Nation> centers() { return centers; }
    public List<Slot> slots() { return slots; }

    public Unit unitAt(Province province) {
        return province == null ? null : occupants[Province.canonical(province).ordinal()];
    }

    public Nation centerOwner(Province province) {
        return centers.get(Province.canonical(province));
    }

    public void requirePrefix(List<Integer> action) {
        Objects.requireNonNull(action, "action");
        if (action.size() > slots.size())
            throw new IllegalArgumentException("Too many order indices");
        for (int slot = 0; slot < action.size(); slot++) {
            Integer index = action.get(slot);
            if (index == null || index < 0 || index >= slots.get(slot).options().size())
                throw new IllegalArgumentException("Invalid option index at slot " + slot);
        }
    }

    public void requireAction(List<Integer> action) {
        requirePrefix(action);
        if (action.size() != slots.size())
            throw new IllegalArgumentException("A complete action needs one index per slot");
    }
}