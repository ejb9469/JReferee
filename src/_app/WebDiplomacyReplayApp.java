package _app;

import agents.generation.ActionCandidate;
import agents.generation.CatalogActionValidator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import game.*;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import parsing.webdiplomacy.WebDiplomacyAdapter;
import parsing.webdiplomacy.WebDiplomacyAdapter.*;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;

import static io.json.JsonEscaper.appendString;


/**
 * Runs a pinned TRAIN-only mechanics pilot without opening a catalog or model.
 */
public final class WebDiplomacyReplayApp {


    private static final String ZIP_HASH = "451b2a662c1575d5f9c965d36e3a99dc25b23550d1af2432309180853a084024";
    private static final String INVENTORY_HASH = "d576bb5fcf140ccbbcddd70043f8c1759216d4bf316ce26e834af9fc2d1f832c";
    private static final List<String> KEYS =
            List.of("159837", "150854", "140643", "138325", "173163", "140665", "443777", "175882");
    private static final JsonAccess JSON = new JsonAccess(ParseException::new);


    private WebDiplomacyReplayApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: WebDiplomacyReplayApp <reviewed-fixtures.zip> [new-report.json]");
        Path output = args.length == 2 ? Path.of(args[1])
                : Path.of("data", "webdiplomacy-replay-" + UUID.randomUUID() + ".json");
        require(!Files.exists(output) && !Files.isSymbolicLink(output), "Report already exists");
        List<SourceGame> games = readBundle(Path.of(args[0]));
        List<Report> reports = new ArrayList<>();
        for (SourceGame game : games) {
            Report report = replay(game);
            reports.add(report);
            System.out.println("[PILOT] " + game.key() + ": " + report.counts() + "; findings=" + report.findings().size());
        }
        boolean reviewRequired = reports.stream().anyMatch(report -> !report.findings().isEmpty());
        StringBuilder out = new StringBuilder("{\"schema\":\"jreferee-webdiplomacy-replay-pilot-v2\",")
                .append("\"complete\":true,\"review_required\":").append(reviewRequired)
                .append(",\"admission_performed\":false,\"split\":\"TRAIN\",")
                .append("\"split_seed\":7,\"fixture_zip_sha256\":");
        appendString(out, ZIP_HASH);
        out.append(",\"games\":[");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0)
                out.append(',');
            reports.get(i).appendJson(out);
        }
        out.append("]}\n");
        Files.writeString(output, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Report: " + output.toAbsolutePath());
        System.out.println("Diagnostic pilot only. No training admission, held-out access or model change.");
        if (reviewRequired)
            System.exit(2);

    }

    public static List<SourceGame> readBundle(Path path) throws Exception {

        byte[] snapshot;
        try (var input = Files.newInputStream(path)) {
            snapshot = input.readNBytes(32 * 1024 * 1024 + 1);
        }
        require(snapshot.length <= 32 * 1024 * 1024, "Fixture ZIP exceeds the pilot size bound");
        require(hash(snapshot).equals(ZIP_HASH), "Fixture ZIP differs from the reviewed receipt");
        Set<String> expected = new HashSet<>(Set.of("manifest.json"));
        KEYS.forEach(key -> expected.add("games/" + key + ".json"));
        Map<String, String> contents = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(snapshot))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                require(expected.contains(entry.getName()) && !entry.isDirectory(), "Unexpected ZIP member");
                byte[] bytes = zip.readNBytes(8 * 1024 * 1024 + 1);
                require(bytes.length <= 8 * 1024 * 1024, "ZIP member exceeds the pilot size bound");
                require(contents.putIfAbsent(entry.getName(), new String(bytes, StandardCharsets.UTF_8)) == null,
                        "Duplicate ZIP member");
            }
        }
        require(contents.keySet().equals(expected), "Missing ZIP members");
        Map<String, Object> manifest = JSON.object(JsonReader.read(contents.get("manifest.json")), "manifest");
        require(Boolean.TRUE.equals(manifest.get("complete"))
                        && Boolean.FALSE.equals(manifest.get("admission_performed"))
                        && "jreferee-webdiplomacy-fixture-bundle-v1".equals(manifest.get("schema"))
                        && JSON.integer(manifest.get("split_seed"), "split_seed") == 7
                        && "TRAIN".equals(manifest.get("split")) && INVENTORY_HASH.equals(manifest.get("inventory_sha256")),
                "Fixture manifest differs");
        List<Object> records = JSON.array(manifest.get("games"), "games");
        require(records.size() == KEYS.size(), "Unexpected fixture count");
        List<SourceGame> games = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = JSON.object(records.get(i), "games[" + i + "]");
            String key = KEYS.get(i);
            require(key.equals(record.get("game_key")) && ("games/" + key + ".json").equals(record.get("file")),
                    "Fixture selection differs");
            String raw = contents.get("games/" + key + ".json");
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            require(bytes.length == JSON.integer(record.get("bytes"), "bytes")
                    && hash(bytes).equals(record.get("sha256")), "Projected fixture receipt differs");
            SourceGame game = WebDiplomacyAdapter.readFixture(raw);
            require(game.key().equals(key) && game.sourceHash().equals(record.get("source_payload_sha256"))
                    && game.pressType().equals(record.get("press_type")), "Fixture provenance differs");
            games.add(game);
        }
        return List.copyOf(games);

    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static Report replay(SourceGame source) {

        WebDiplomacyAdapter.verifyOwnership(source.frames());
        Report report = new Report(source.key(), source.sourceHash());
        Frame last = source.frames().getLast();
        Set<Province> occupied = new HashSet<>();
        boolean finalOverlap = false;
        for (PlacedUnit unit : last.units())
            if (!occupied.add(Province.canonical(unit.location()))) {
                report.find(last, "FINAL_UNIT_OVERLAP", "Multiple source units at " + Province.canonical(unit.location()));
                finalOverlap = true;
            }
        report.add("source_frames", source.frames().size());
        report.add("source_orders", source.frames().stream().mapToInt(frame -> frame.orders().size()).sum());
        for (Frame frame : source.frames())
            if (frame.reportedCenters().size() < 34) {
                report.add("sparse_center_frames", 1);
                report.add("implicit_neutral_center_entries", 34 - frame.reportedCenters().size());
            }

        for (int index = 0; index < source.frames().size(); index++) {
            Frame root = source.frames().get(index);
            if (!root.name().equals("Diplomacy"))
                continue;
            report.add("movement_roots", 1);
            int before = report.findings().size();
            if (root.reportedCenters().size() != 34)
                report.add("sparse_movement_roots", 1);
            Game game = Game.fromState(new GameState_Movement(root.moment(), root.board()));
            try {
                List<Order> orders = WebDiplomacyAdapter.movement(root, game.board());
                if (!legal(game.snapshot(), root, orders, report))
                    continue;
                MovementResult result = game.resolveMovement(orders);
                report.add("movements_resolved", 1);
                compareMovement(root, result, report);
                for (int next = index + 1; next < source.frames().size(); next++) {
                    Frame frame = source.frames().get(next);
                    if (frame.name().equals("Finished")) {
                        report.add("final_center_updates_unverified", 1);
                        if (finalOverlap)
                            report.find(frame, "FINAL_SNAPSHOT_EXCLUDED", "Overlapping source board retained as diagnostic evidence");
                        else
                            compareUnits(frame, game.snapshot(), report);
                        break;
                    }
                    if (frame.name().equals("Diplomacy") && game.phase().isAdjustment()) {
                        if (!legal(game.snapshot(), frame, List.of(), report))
                            break;
                        game.resolveAdjustments(List.of());
                        report.add("omitted_empty_build_phases", 1);
                    }
                    if (!game.snapshot().moment().equals(frame.moment())) {
                        report.find(frame, "PHASE_MISMATCH", "Calculated " + game.snapshot().moment());
                        break;
                    }
                    boolean unitsMatch = compareUnits(frame, game.snapshot(), report);
                    boolean ownersMatch = game.board().owners().equals(frame.owners());
                    report.add("ownership_comparisons", 1);
                    if (!ownersMatch)
                        report.find(frame, "OWNERSHIP_MISMATCH",
                                "Source=" + frame.owners() + "; calculated=" + game.board().owners());
                    if (!unitsMatch || !ownersMatch || frame.name().equals("Diplomacy"))
                        break;
                    if (game.snapshot() instanceof GameState_Retreats retreats) {
                        var submitted = WebDiplomacyAdapter.retreats(frame, retreats);
                        if (!legal(retreats, frame, submitted, report))
                            break;
                        game.resolveRetreats(submitted);
                        report.add("retreats_resolved", 1);
                    } else if (game.phase().isAdjustment()) {
                        var submitted = WebDiplomacyAdapter.adjustments(frame, game.board());
                        if (!legal(game.snapshot(), frame, submitted, report))
                            break;
                        game.resolveAdjustments(submitted);
                        report.add("adjustments_resolved", 1);
                    } else {
                        throw new IllegalStateException("Unexpected successor phase");
                    }
                }
            } catch (UnrepresentedConvoyException exception) {
                report.find(root, "UNREPRESENTED_CONVOY_CHOICE", exception.getMessage());
            } catch (ParseException exception) {
                report.find(root, "SOURCE_ORDER_ERROR", exception.getMessage());
            }
            if (report.findings().size() == before)
                report.add("roots_without_findings", 1);
        }
        return report;

    }

    private static boolean legal(GameState state, Frame frame, Collection<? extends OrderForm> orders, Report report) {

        CatalogActionValidator validator = new CatalogActionValidator();
        boolean valid = true;
        for (Nation nation : Nation.values()) {
            var submitted = orders.stream().filter(order -> order.owner() == nation).toList();
            ActionCandidate candidate = new ActionCandidate(nation, submitted, 0, ActionCandidate.Source.POLICY);
            var errors = validator.validate(state, candidate);
            report.add("catalog_actions_checked", 1);
            if (!errors.isEmpty()) {
                report.find(frame, "CATALOG_ACTION_ERROR", nation + ": " + errors);
                valid = false;
            }
        }
        return valid;

    }

    private static void compareMovement(Frame root, MovementResult result, Report report) {

        Map<UnitId, Province> locations = root.board().locations();
        for (Submission source : root.orders()) {
            UnitId unit = WebDiplomacyAdapter.unitAt(locations, source);
            if (source.type().equals("Move")) {
                report.add("move_results_checked", 1);
                if (source.success() != result.outcomes().get(unit).successfulMove())
                    report.find(root, "MOVE_RESULT_MISMATCH", source.toString());
            }
            report.add("dislodgement_results_checked", 1);
            if (source.dislodged() != result.dislodgements().containsKey(unit))
                report.find(root, "DISLODGEMENT_MISMATCH", source.toString());
        }

    }

    private static boolean compareUnits(Frame frame, GameState state, Report report) {

        Map<String, Integer> expected = new TreeMap<>();
        Map<String, Integer> actual = new TreeMap<>();
        frame.units().forEach(unit -> expected.merge(unitKey(unit), 1, Integer::sum));
        state.board().locations().forEach((unit, location) ->
                actual.merge(unitKey(new PlacedUnit(unit.owner(), unit.unitType(), location, false)), 1, Integer::sum));
        if (state instanceof GameState_Retreats retreats)
            retreats.movementResult().dislodgements().forEach((unit, result) ->
                    actual.merge(unitKey(new PlacedUnit(unit.owner(), unit.unitType(), result.displacedFrom(), true)), 1, Integer::sum));
        report.add("unit_snapshot_comparisons", 1);
        if (expected.equals(actual))
            return true;
        report.find(frame, "UNIT_SNAPSHOT_MISMATCH", "Missing=" + difference(expected, actual)
                + "; unexpected=" + difference(actual, expected));
        return false;

    }

    private static String unitKey(PlacedUnit unit) {
        return unit.owner() + " " + unit.type() + " " + unit.location() + (unit.retreating() ? " DISLODGED" : "");
    }

    private static Map<String, Integer> difference(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> result = new TreeMap<>();
        left.forEach((key, count) -> {
            int remaining = count - right.getOrDefault(key, 0);
            if (remaining > 0)
                result.put(key, remaining);
        });
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }


    public record Finding(int turn, String phase, String code, String detail) { }

    public static final class Report {

        private final String key;
        private final String sourceHash;
        private final Map<String, Integer> counts = new TreeMap<>();
        private final List<Finding> findings = new ArrayList<>();

        private Report(String key, String sourceHash) {
            this.key = key;
            this.sourceHash = sourceHash;
        }

        public Map<String, Integer> counts() {
            return Collections.unmodifiableMap(counts);
        }

        public List<Finding> findings() {
            return Collections.unmodifiableList(findings);
        }

        private void add(String name, int count) {
            counts.merge(name, count, Integer::sum);
        }

        private void find(Frame frame, String code, String detail) {
            findings.add(new Finding(frame.turn(), frame.name(), code, detail));
        }

        public void appendJson(StringBuilder out) {
            out.append("{\"game_key\":");
            appendString(out, key);
            out.append(",\"source_payload_sha256\":");
            appendString(out, sourceHash);
            out.append(",\"counts\":{");
            boolean comma = false;
            for (var entry : counts.entrySet()) {
                if (comma)
                    out.append(',');
                appendString(out, entry.getKey());
                out.append(':').append(entry.getValue());
                comma = true;
            }
            out.append("},\"findings\":[");
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0)
                    out.append(',');
                Finding finding = findings.get(i);
                out.append("{\"turn\":").append(finding.turn()).append(",\"phase\":");
                appendString(out, finding.phase());
                out.append(",\"code\":");
                appendString(out, finding.code());
                out.append(",\"detail\":");
                appendString(out, finding.detail());
                out.append('}');
            }
            out.append("]}");
        }

    }

}