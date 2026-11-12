package agents.encoding;

import contracts.OrderForms;
import domain.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BiPredicate;

import static io.json.JsonEscaper.appendString;

/**
 * Stable numeric vocabulary and standard-map tables for movement networks.
 * Zero denotes an absent categorical value. Board rows exclude split coasts
 * and Switzerland; exact location IDs retain the six split coasts.
 */
public final class NeuralMovementSpec {

    public static final String SCHEMA = "jreferee-neural-movement-v1";
    public static final int BOARD_ROWS = 75, BOARD_FIELDS = 4, ORDER_FIELDS = 5;
    public static final int YEAR_ORIGIN = 1901, YEAR_SCALE = 20;
    public static final List<String> LOCATIONS = List.of((
            "NONE ADR AEG Alb Ank Apu Arm BAL BAR BLA BOT Bel Ber Boh Bre Bud Bul BulEC BulSC Bur "
                    + "Cly Con Den EAS ENG Edi Fin Gal Gas Gre HEL Hol ION IRI Kie LYO Lon Lvn Lvp MAO Mar Mos Mun "
                    + "NAO NTH NWG Naf Nap Nwy Par Pic Pie Por Pru Rom Ruh Rum SKA Ser Sev Sil Smy Spa SpaNC SpaSC "
                    + "Stp StpNC StpSC Swe Syr TYS Tri Tun Tus Tyr Ukr Ven Vie WES Wal War Yor").split(" "));
    public static final List<String> POWERS = List.of("NONE", "AUSTRIA", "ENGLAND", "FRANCE",
            "GERMANY", "ITALY", "RUSSIA", "TURKEY");
    public static final List<String> UNIT_TYPES = List.of("NONE", "ARMY", "FLEET");
    public static final List<String> ORDER_TYPES = List.of("NONE", "HOLD", "MOVE", "SUPPORT", "CONVOY");
    public static final List<String> PHASES = List.of("NONE", "SPRING_MOVEMENT", "FALL_MOVEMENT");

    private static final Map<Province, Integer> LOCATION_IDS = new EnumMap<>(Province.class);
    private static final List<Province> BOARD;
    private static final Map<Province, Integer> BOARD_IDS = new EnumMap<>(Province.class);
    private static final String JSON, HASH;

    static {
        List<Province> board = new ArrayList<>();
        for (int id = 1; id < LOCATIONS.size(); id++) {
            Province location = Province.valueOf(LOCATIONS.get(id));
            if (LOCATION_IDS.put(location, id) != null)
                throw new IllegalStateException("Duplicate neural location");
            if (location.parent == null) {
                BOARD_IDS.put(location, board.size());
                board.add(location);
            }
        }
        if (board.size() != BOARD_ROWS || LOCATIONS.size() != 82
                || LOCATION_IDS.size() != Province.values().length - 1)
            throw new IllegalStateException("Standard map differs from the neural vocabulary");
        BOARD = List.copyOf(board);
        JSON = describe() + "\n";
        HASH = sha256(JSON.getBytes(StandardCharsets.UTF_8));
    }

    private NeuralMovementSpec() { }

    public static int locationId(Province location) {
        if (location == null) return 0;
        Integer id = LOCATION_IDS.get(location);
        if (id == null) throw new IllegalArgumentException("Unsupported neural location: " + location);
        return id;
    }

    public static Province location(int id) {
        if (id < 0 || id >= LOCATIONS.size()) throw new IllegalArgumentException("Unknown location ID: " + id);
        return id == 0 ? null : Province.valueOf(LOCATIONS.get(id));
    }

    public static int boardRow(Province location) {
        Objects.requireNonNull(location, "location");
        locationId(location);
        return BOARD_IDS.get(Province.canonical(location));
    }

    public static Province boardLocation(int row) { return BOARD.get(row); }
    public static int powerId(Nation power) { return power == null ? 0 : id(POWERS, power.name()); }
    public static int unitTypeId(UnitType type) { return id(UNIT_TYPES, Objects.requireNonNull(type, "type").name()); }
    public static int orderTypeId(OrderType type) { return id(ORDER_TYPES, Objects.requireNonNull(type, "type").name()); }
    public static int phaseId(game.GamePhase phase) { return id(PHASES, Objects.requireNonNull(phase, "phase").name()); }
    public static String toJson() { return JSON; }
    public static String sha256() { return HASH; }

    private static int id(List<String> names, String name) {
        int id = names.indexOf(name);
        if (id <= 0) throw new IllegalArgumentException("Unsupported neural category: " + name);
        return id;
    }

    private static String describe() {
        StringBuilder out = new StringBuilder("{\"schema\":");
        appendString(out, SCHEMA);
        out.append(",\"input_encoding\":");
        appendString(out, MovementEncoding.SCHEMA);
        out.append(",\"map\":\"STANDARD\",\"locations\":");
        strings(out, LOCATIONS);
        out.append(",\"powers\":"); strings(out, POWERS);
        out.append(",\"unit_types\":"); strings(out, UNIT_TYPES);
        out.append(",\"order_types\":"); strings(out, ORDER_TYPES);
        out.append(",\"phases\":"); strings(out, PHASES);
        out.append(",\"board_fields\":");
        strings(out, List.of("unit_type", "unit_owner", "unit_location", "center_owner"));
        out.append(",\"order_fields\":");
        strings(out, List.of("unit_type", "origin", "order_type", "target", "auxiliary"));
        out.append(",\"context_fields\":");
        strings(out, List.of("power", "phase", "year"));
        out.append(",\"year_origin\":").append(YEAR_ORIGIN).append(",\"year_scale\":").append(YEAR_SCALE);
        out.append(",\"year_transform\":\"float32(year - year_origin) / float32(year_scale); no clipping\"");
        out.append(",\"board_locations\":");
        integers(out, BOARD.stream().map(NeuralMovementSpec::locationId).toList());
        out.append(",\"canonical_locations\":");
        integers(out, LOCATIONS.stream().map(name -> name.equals("NONE") ? 0
                : locationId(Province.canonical(Province.valueOf(name)))).toList());
        out.append(",\"location_board_rows\":");
        integers(out, LOCATIONS.stream().map(name -> name.equals("NONE") ? -1 : boardRow(Province.valueOf(name))).toList());
        out.append(",\"terrain_names\":"); strings(out, List.of("INLAND", "COASTAL", "SEA"));
        out.append(",\"terrain\":");
        integers(out, BOARD.stream().map(p -> p.geography == Geography.WATER ? 2 : p.hasCoast() ? 1 : 0).toList());
        out.append(",\"supply_centers\":");
        integers(out, BOARD.stream().map(p -> p.supplyCenter ? 1 : 0).toList());
        out.append(",\"home_owners\":");
        integers(out, BOARD.stream().map(p -> powerId(p.homeowner)).toList());
        out.append(",\"army_direct\":");
        edges(out, (a, b) -> OrderForms.isValidUnitLocation(UnitType.ARMY, a)
                && OrderForms.isValidUnitLocation(UnitType.ARMY, b) && a != b && a.isAdjacentTo(b));
        out.append(",\"fleet_direct\":");
        edges(out, (a, b) -> OrderForms.isPotentialMove(UnitType.FLEET, a, b));
        out.append(",\"potential_convoy\":");
        edges(out, OrderForms::isPotentialConvoyMove);
        out.append(",\"army_support\":");
        edges(out, (a, b) -> b.parent == null && !Province.equalsIgnoreCoast(a, b)
                && OrderForms.canSupportTo(UnitType.ARMY, a, b));
        out.append(",\"fleet_support\":");
        edges(out, (a, b) -> b.parent == null && !Province.equalsIgnoreCoast(a, b)
                && OrderForms.canSupportTo(UnitType.FLEET, a, b));
        return out.append('}').toString();
    }

    private static void edges(StringBuilder out, BiPredicate<Province, Province> allowed) {
        out.append('[');
        boolean first = true;
        for (int from = 1; from < LOCATIONS.size(); from++)
            for (int to = 1; to < LOCATIONS.size(); to++)
                if (allowed.test(location(from), location(to))) {
                    if (!first) out.append(',');
                    first = false;
                    out.append('[').append(from).append(',').append(to).append(']');
                }
        out.append(']');
    }

    private static void strings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) out.append(',');
            appendString(out, values.get(index));
        }
        out.append(']');
    }

    private static void integers(StringBuilder out, List<Integer> values) {
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) out.append(',');
            out.append(values.get(index));
        }
        out.append(']');
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}