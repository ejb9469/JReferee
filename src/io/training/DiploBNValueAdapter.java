package io.training;

import domain.Nation;
import game.BoardState;
import game.GameMoment;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import misc.scoring.ScoringSystem;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;

import java.util.*;

/**
 * Converts consistent source-recorded completions into value observations.
 * It does not replay adjudication or infer why a non-solo game ended.
 */
public final class DiploBNValueAdapter {


    // Result contract \\

    public enum Rejection {

        UNSUPPORTED_SOURCE,
        PAYLOAD_HASH_MISMATCH,
        INVALID_SOURCE_DATA,
        NO_ENDING_FRAME,
        DATA_AFTER_ENDING,
        CONFLICTING_ENDINGS,
        FINAL_COUNTS_DISAGREE,
        NONCHRONOLOGICAL_POSITIONS,
        EARLIER_SOLO_POSITION,
        NO_SUPPORTED_POSITIONS

    }

    public record Result(
            Optional<ValueDataset.GameExample> game,
            Optional<Rejection> rejection,
            List<String> diagnostics
    ) {

        public Result {

            Objects.requireNonNull(game, "game");
            Objects.requireNonNull(rejection, "rejection");
            diagnostics = List.copyOf(diagnostics);

            if (game.isPresent() == rejection.isPresent())
                throw new IllegalArgumentException(
                        "Supply either a game or a rejection reason");

        }

    }


    // Construction \\

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    public DiploBNValueAdapter() { }


    // Source admission \\

    public Result read(
            String source,
            String gameKey,
            String payload,
            String expectedHash
    ) {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(gameKey, "gameKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expectedHash, "expectedHash");

        if (source.isBlank() || gameKey.isBlank())
            throw new IllegalArgumentException("Source and game key are required");

        List<String> diagnostics = new ArrayList<>();

        if (!source.equals("DIPLOBN"))
            return rejected(Rejection.UNSUPPORTED_SOURCE, diagnostics);

        if (!SourceFingerprints.sha256Hex(payload).equals(expectedHash))
            return rejected(Rejection.PAYLOAD_HASH_MISMATCH, diagnostics);

        try {
            return this.decode(source, gameKey, payload, expectedHash, diagnostics);
        } catch (IllegalArgumentException exception) {
            diagnostics.add("Invalid source structure, board or outcome counts.");
            return rejected(Rejection.INVALID_SOURCE_DATA, diagnostics);
        }

    }

    private Result decode(
            String source,
            String gameKey,
            String payload,
            String expectedHash,
            List<String> diagnostics
    ) {

        Map<String, Object> raw = JSON.object(JsonReader.read(payload), "$");
        SourceGameMetadata metadata = new SourceGameMetadata(raw);

        if (metadata.warningCount() > 0)
            diagnostics.add("Source metadata warnings: " + metadata.warningCount());

        List<Object> rawPhases = JSON.array(
                JSON.required(raw, "GamePhases", "$"), "$.GamePhases");

        List<Object> projected = new ArrayList<>();

        for (int index = 0; index < rawPhases.size(); index++) {

            String path = "$.GamePhases[" + index + "]";
            Map<String, Object> phase = JSON.object(rawPhases.get(index), path);
            Map<String, Object> boardOnly = new LinkedHashMap<>();

            boardOnly.put("Phase", JSON.integer(
                    JSON.required(phase, "Phase", path), path + ".Phase"));
            boardOnly.put("Status", JSON.string(
                    JSON.required(phase, "Status", path), path + ".Status"));
            boardOnly.put("Units", JSON.object(
                    JSON.required(phase, "Units", path), path + ".Units"));
            boardOnly.put("SupplyCenters", JSON.object(
                    JSON.required(phase, "SupplyCenters", path), path + ".SupplyCenters"));

            projected.add(boardOnly);

        }

        DiploBNGame parsed = new DiploBNParser().parse(
                ValueOutcomeAudit.json(Map.of("GamePhases", projected)));

        List<DiploBNPhase> phases = parsed.phases();
        int endingIndex = -1;
        DiploBNPhase ending = null;
        int repeatedEndings = 0;

        for (int index = 0; index < phases.size(); index++) {

            DiploBNPhase phase = phases.get(index);

            if (phase.sourceStatus().equals("GameEnded")) {

                if (ending == null) {

                    ending = phase;
                    endingIndex = index;

                } else {

                    if (!moment(ending).equals(moment(phase))
                            || !ending.board().equals(phase.board()))
                        return rejected(Rejection.CONFLICTING_ENDINGS, diagnostics);

                    repeatedEndings++;

                }

            } else if (ending != null) {

                return rejected(Rejection.DATA_AFTER_ENDING, diagnostics);

            }

        }

        if (ending == null)
            return rejected(Rejection.NO_ENDING_FRAME, diagnostics);

        if (repeatedEndings > 0)
            diagnostics.add("Identical repeated ending frames: " + repeatedEndings);

        Map<Nation, Integer> finalCounts = counts(ending.board());
        Map<Nation, Integer> resultCounts = resultCounts(raw.get("ResultSummary"));

        if (!finalCounts.equals(resultCounts))
            return rejected(Rejection.FINAL_COUNTS_DISAGREE, diagnostics);

        boolean solo = finalCounts.values().stream().anyMatch(count -> count >= 18);
        GameMoment endMoment = moment(ending);

        ValueOutcome outcome = new ValueOutcome(
                solo ? ValueOutcome.Termination.SOLO
                        : ValueOutcome.Termination.SOURCE_COMPLETED,
                ValueOutcome.Evidence.SOURCE_RECORDED,
                endMoment,
                finalCounts,
                label(raw.get("ScoringSystem")),
                label(raw.get("LimitType")),
                "DIPLOBN GameEnded frame " + endingIndex
                        + "; final ownership matches all seven ResultSummary counts"
        );

        Object drawSize = raw.get("DrawSize");
        long survivors = finalCounts.values().stream().filter(count -> count > 0).count();
        long expectedDrawSize = solo ? 1 : survivors;

        if (drawSize instanceof Number number
                && number.doubleValue() != expectedDrawSize)
            diagnostics.add("DrawSize differs from the count-based diagnostic; "
                    + "it was not used to assign rewards or infer draw agreement.");

        List<ValueDataset.Position> positions = new ArrayList<>();
        GameMoment previous = null;
        int skippedRetreats = 0;

        for (int index = 0; index < endingIndex; index++) {

            DiploBNPhase phase = phases.get(index);
            GameMoment current = moment(phase);

            if (previous != null && current.compareTo(previous) <= 0)
                return rejected(Rejection.NONCHRONOLOGICAL_POSITIONS, diagnostics);

            if (current.compareTo(endMoment) >= 0)
                return rejected(Rejection.NONCHRONOLOGICAL_POSITIONS, diagnostics);

            previous = current;

            if (counts(phase.board()).values().stream().anyMatch(count -> count >= 18))
                return rejected(Rejection.EARLIER_SOLO_POSITION, diagnostics);

            if (phase.gamePhase().isRetreat()) {
                skippedRetreats++;
                continue;
            }

            positions.add(new ValueDataset.Position(
                    current, phase.board(), ScoringSystem.SUM_OF_SQUARES));

        }

        if (skippedRetreats > 0)
            diagnostics.add("Unsupported retreat positions excluded: " + skippedRetreats);

        if (positions.isEmpty())
            return rejected(Rejection.NO_SUPPORTED_POSITIONS, diagnostics);

        ValueDataset.GameExample game = new ValueDataset.GameExample(
                source,
                gameKey,
                Optional.ofNullable(metadata.communicationType()),
                expectedHash,
                positions,
                outcome
        );

        return new Result(Optional.of(game), Optional.empty(), diagnostics);

    }


    // Source evidence helpers \\

    private static Map<Nation, Integer> resultCounts(Object value) {

        Map<String, Object> summary = JSON.object(value, "$.ResultSummary");
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);

        if (summary.size() != Nation.values().length)
            throw new IllegalArgumentException(
                    "ResultSummary must contain all seven nations");

        for (Nation nation : Nation.values()) {

            String name = nation.name().charAt(0)
                    + nation.name().substring(1).toLowerCase(Locale.ROOT);
            String path = "$.ResultSummary." + name;

            Map<String, Object> result = JSON.object(
                    JSON.required(summary, name, "$.ResultSummary"), path);

            int count = JSON.integer(
                    JSON.required(result, "CenterCount", path), path + ".CenterCount");

            if (count < 0 || count > 34)
                throw new IllegalArgumentException("Invalid source center count");

            counts.put(nation, count);

        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        if (total < 1 || total > 34)
            throw new IllegalArgumentException("Invalid source center total");

        return Collections.unmodifiableMap(counts);

    }

    private static Map<Nation, Integer> counts(BoardState board) {

        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values())
            counts.put(nation, 0);

        for (Nation owner : board.owners().values())
            counts.merge(owner, 1, Integer::sum);

        return Collections.unmodifiableMap(counts);

    }

    private static GameMoment moment(DiploBNPhase phase) {
        return new GameMoment(phase.sourcePhase() / 10, phase.gamePhase());
    }

    private static Optional<String> label(Object value) {
        return value instanceof String text && !text.isBlank()
                ? Optional.of(text) : Optional.empty();
    }

    private static Result rejected(
            Rejection reason,
            List<String> diagnostics
    ) {
        return new Result(Optional.empty(), Optional.of(reason), diagnostics);
    }

}