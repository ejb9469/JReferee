package testing.selfcheck;

import _app.WebDiplomacyReplayApp;
import _app.TrainingSourcePilotApp;
import domain.*;
import game.*;
import parsing.webdiplomacy.WebDiplomacyAdapter;
import parsing.webdiplomacy.WebDiplomacyAdapter.*;
import phase.UnitId;
import phase.adjustments.WaiveOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;


/**
 * Exercises the reviewed TRAIN pilot and source-specific translation guards.
 */
public final class WebDiplomacyAdapterSelfCheck {


    private static int checks;


    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: WebDiplomacyAdapterSelfCheck <reviewed-fixtures.zip>");
        Path input = Path.of(args[0]);
        List<SourceGame> games = WebDiplomacyReplayApp.readBundle(input);
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Integer> findings = new TreeMap<>();
        for (SourceGame game : games) {
            var report = WebDiplomacyReplayApp.replay(game);
            report.counts().forEach((name, count) -> totals.merge(name, count, Integer::sum));
            report.findings().forEach(finding -> findings.merge(finding.code(), 1, Integer::sum));
        }
        check(totals.get("movements_resolved") == 121, "Every representable movement root must resolve");
        check(totals.get("retreats_resolved") == 84 && totals.get("adjustments_resolved") == 55,
                "Representable successor retreats and adjustments must resolve");
        check(totals.get("move_results_checked") == 1964
                && totals.get("dislodgement_results_checked") == 3824, "Result comparisons must cover translated submissions");
        check(totals.get("unit_snapshot_comparisons") == 259
                && totals.get("ownership_comparisons") == 253, "Successor snapshots must be compared");
        check(findings.equals(Map.of("FINAL_UNIT_OVERLAP", 2,
                        "UNREPRESENTED_CONVOY_CHOICE", 2, "FINAL_SNAPSHOT_EXCLUDED", 1)),
                "Unexpected source, legality or replay findings: " + findings);
        check(totals.get("catalog_actions_checked") == 1820, "All processed power actions must be legality-checked");
        check(totals.get("sparse_center_frames") == 29 && totals.get("implicit_neutral_center_entries") == 95
                && totals.get("sparse_movement_roots") == 17, "Sparse neutral records must remain visible");
        check(totals.get("roots_without_findings") == 120, "Neutral omission alone must not quarantine a root");

        SourceGame first = games.getFirst();
        Frame opening = first.frames().getFirst();
        List<GameState_Movement> originalStates = first.frames().stream()
                .filter(frame -> frame.name().equals("Diplomacy"))
                .map(frame -> new GameState_Movement(frame.moment(), frame.board())).toList();
        List<GameState_Movement> freshIdentities = originalStates.stream().map(state -> {
            Map<UnitId, Province> locations = new LinkedHashMap<>();
            state.board().locations().forEach((unit, location) -> locations.put(
                    new UnitId(UUID.randomUUID(), unit.owner(), unit.unitType(), location), location));
            return new GameState_Movement(state.moment(), new BoardState(locations, state.board().owners()));
        }).toList();
        check(TrainingSourcePilotApp.fingerprint(originalStates).equals(
                TrainingSourcePilotApp.fingerprint(freshIdentities)), "Gameplay fingerprints must ignore UnitId values");
        List<Submission> changedResults = opening.orders().stream().map(order -> new Submission(
                order.type(), order.owner(), order.unitType(), order.origin(), order.to(), order.from(),
                order.viaConvoy(), !order.success(), !order.dislodged())).toList();
        Frame changed = withOrders(opening, changedResults);
        check(WebDiplomacyAdapter.movement(opening, opening.board()).equals(
                WebDiplomacyAdapter.movement(changed, opening.board())), "Source outcomes must not alter submitted orders");
        List<Frame> changedFrames = new ArrayList<>(first.frames());
        changedFrames.set(0, changed);
        var altered = WebDiplomacyReplayApp.replay(new SourceGame(first.key(), first.sourceHash(), first.pressType(), changedFrames));
        check(altered.findings().stream().anyMatch(finding -> finding.code().equals("MOVE_RESULT_MISMATCH")),
                "Altered outcomes must be diagnosed");
        check(altered.findings().stream().noneMatch(finding -> finding.code().equals("UNIT_SNAPSHOT_MISMATCH")),
                "Calculated positions must remain independent of source result flags");

        Frame adjacent = games.stream().filter(game -> game.key().equals("138325")).findFirst().orElseThrow()
                .frames().stream().filter(frame -> frame.turn() == 5 && frame.name().equals("Diplomacy")).findFirst().orElseThrow();
        rejects(() -> WebDiplomacyAdapter.movement(adjacent, adjacent.board()), "Explicit adjacent convoy");
        Frame landWithConvoy = games.stream().filter(game -> game.key().equals("443777")).findFirst().orElseThrow()
                .frames().stream().filter(frame -> frame.turn() == 13 && frame.name().equals("Diplomacy")).findFirst().orElseThrow();
        rejects(() -> WebDiplomacyAdapter.movement(landWithConvoy, landWithConvoy.board()), "Land move with matching convoy");

        Frame fall = first.frames().stream().filter(frame -> frame.turn() == 1 && frame.name().equals("Diplomacy")).findFirst().orElseThrow();
        Map<Province, Nation> lostOwners = new LinkedHashMap<>(fall.owners());
        lostOwners.remove(Province.Lon);
        Set<Province> missingRows = new HashSet<>(fall.reportedCenters());
        missingRows.remove(Province.Lon);
        rejects(() -> WebDiplomacyAdapter.verifyOwnership(List.of(opening,
                        new Frame(fall.turn(), fall.name(), fall.units(), lostOwners, missingRows, fall.orders()))),
                "Previously owned center row removed");
        rejects(() -> WebDiplomacyAdapter.verifyOwnership(List.of(opening,
                        new Frame(fall.turn(), fall.name(), fall.units(), lostOwners, fall.reportedCenters(), fall.orders()))),
                "Previously owned center changed to explicit neutral");
        Map<Province, Nation> wrongOwners = new LinkedHashMap<>(fall.owners());
        wrongOwners.put(Province.Lon, Nation.FRANCE);
        List<Frame> wrongFrames = new ArrayList<>(first.frames());
        wrongFrames.set(wrongFrames.indexOf(fall), new Frame(fall.turn(), fall.name(), fall.units(),
                wrongOwners, fall.reportedCenters(), fall.orders()));
        var wrongReplay = WebDiplomacyReplayApp.replay(
                new SourceGame(first.key(), first.sourceHash(), first.pressType(), wrongFrames));
        check(wrongReplay.findings().stream().anyMatch(finding -> finding.code().equals("OWNERSHIP_MISMATCH")),
                "Replay must detect a positive ownership change inconsistent with adjudication");
        Frame builds = first.frames().stream().filter(frame -> frame.turn() == 1 && frame.name().equals("Builds")).findFirst().orElseThrow();
        Province captured = builds.owners().keySet().stream()
                .filter(center -> !fall.owners().containsKey(center)).findFirst().orElseThrow();
        Map<Province, Nation> uncaptured = new LinkedHashMap<>(builds.owners());
        uncaptured.remove(captured);
        Set<Province> absent = new HashSet<>(builds.reportedCenters());
        absent.remove(captured);
        List<Frame> lostCapture = new ArrayList<>(first.frames());
        lostCapture.set(lostCapture.indexOf(builds), new Frame(builds.turn(), builds.name(), builds.units(),
                uncaptured, absent, builds.orders()));
        check(WebDiplomacyReplayApp.replay(new SourceGame(first.key(), first.sourceHash(),
                                first.pressType(), lostCapture)).findings().stream()
                        .anyMatch(finding -> finding.code().equals("OWNERSHIP_MISMATCH")),
                "Replay must detect an omitted newly captured center");
        Game engine = Game.fromState(new GameState_Movement(fall.moment(), fall.board()));
        engine.resolveMovement(WebDiplomacyAdapter.movement(fall, engine.board()));
        Submission build = builds.orders().getFirst();
        check(build.type().equals("Build Army") || build.type().equals("Build Fleet"), "Expected a source build");
        List<Submission> waived = new ArrayList<>(builds.orders());
        waived.set(0, new Submission("Wait", build.owner(), null, null, null, null, false, true, false));
        var submitted = WebDiplomacyAdapter.adjustments(withOrders(builds, waived), engine.board());
        check(submitted.getFirst() instanceof WaiveOrder, "A nullable Wait must consume a build without creating a unit");
        var result = engine.resolveAdjustments(submitted);
        Frame spring = first.frames().stream().filter(frame -> frame.turn() == 2 && frame.name().equals("Diplomacy")).findFirst().orElseThrow();
        check(result.finalLocations().size() == spring.units().size() - 1, "The waived source build must remain absent");

        try (ZipFile zip = new ZipFile(input.toFile())) {
            String raw;
            try (var stream = zip.getInputStream(zip.getEntry("games/159837.json"))) {
                raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            String source = raw;
            rejects(() -> WebDiplomacyAdapter.readFixture(source.replace("\"split\": \"TRAIN\"", "\"split\": \"TEST\"")), "Held-out fixture");
            rejects(() -> WebDiplomacyAdapter.readFixture(source.replace("\"unitType\": \"Fleet\"", "\"unitType\": null")), "Missing movement unit type");
            rejects(() -> WebDiplomacyAdapter.readFixture(source.replace("Bulgaria (North Coast)", "Bulgaria (East Coast)")), "Unexpected coast vocabulary");
            rejects(() -> WebDiplomacyAdapter.readFixture(source.replace("\"buildAnywhere\": false", "\"buildAnywhere\": true")), "Nonstandard build rule");
        }
        Path changedZip = Files.createTempFile("webdip-fixture-copy-", ".zip");
        try {
            byte[] bytes = Files.readAllBytes(input);
            bytes[0] ^= 1;
            Files.write(changedZip, bytes);
            rejects(() -> WebDiplomacyReplayApp.readBundle(changedZip), "Changed ZIP receipt");
        } finally {
            Files.delete(changedZip);
        }
        System.out.println("WebDiplomacyAdapterSelfCheck passed: " + checks + " checks");

    }

    private static Frame withOrders(Frame original, List<Submission> orders) {
        return new Frame(original.turn(), original.name(), original.units(), original.owners(), original.reportedCenters(), orders);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void rejects(Checked action, String message) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

}