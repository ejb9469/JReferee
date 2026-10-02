package parsing.diplobn;

import domain.Nation;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GameMoment;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.adjustments.WaiveOrder;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;


/**
 * Replays source submissions using local adjudication.
 *
 * Source boards verify positions; source annotations are checked separately.
 * This does not turn local outcomes into source-authoritative outcomes.
 */
public class DiploBNRecordImporter {

    public static final String RULESET =
            "jreferee-standard-szykman-local-replay-v3";


    public record ImportResult(GameRecord game, List<String> notes) {

        public ImportResult {
            Objects.requireNonNull(game, "game");
            notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        }

    }


    /**
     * Compatibility entry point.
     * Use importWithReport when provenance notes need to be retained.
     */
    public GameRecord importGame(long sourceGameId, DiploBNGame source) {
        return importWithReport(sourceGameId, source).game();
    }

    public ImportResult importWithReport(long sourceGameId, DiploBNGame source) {

        if (sourceGameId < 1)
            throw new IllegalArgumentException("DiploBN GameID must be positive");

        Objects.requireNonNull(source, "source");

        List<DiploBNPhase> phases = source.phases();

        if (phases.size() < 2)
            throw new IllegalArgumentException(
                    "At least two snapshots are needed to verify a replayed phase");

        Game game = StandardGameFactory.create1901();
        GameRecordBuilder archive = new GameRecordBuilder(
                archiveId(sourceGameId), RULESET, game);

        List<String> notes = new ArrayList<>();

        MovementResult pendingMovement = null;
        BoardState precedingMovementBoard = null;
        long sequence = 0;

        for (int index = 0; index < phases.size(); index++) {

            DiploBNPhase phase = phases.get(index);
            GameMoment sourceMoment = momentOf(phase);

            try {

                // Some exports omit winters with no observed board change.
                if (game.phase() == GamePhase.WINTER_ADJUSTMENT
                        && sourceMoment.year() == game.year() + 1
                        && sourceMoment.gamePhase() == GamePhase.SPRING_MOVEMENT) {

                    requireSamePosition(
                            game.board(), phase.board(),
                            "Omitted Winter changed units or ownership");

                    requireNoMandatoryDisbands(game.board());

                    GameMoment winter = currentMoment(game);

                    archive.resolveAdjustments(
                            List.of(), resolutionMoment(game, sequence++));

                    requireSamePosition(
                            game.board(), phase.board(),
                            "Reconstructed Winter does not match the next source board");

                    notes.add(winter
                            + ": reconstructed unchanged Winter; "
                            + "adjustment submissions were not observed");

                }

                GameMoment expected = currentMoment(game);

                if (!sourceMoment.equals(expected))
                    throw new IllegalArgumentException(
                            "Expected " + expected + ", found " + sourceMoment);

                if (phase.gamePhase().isRetreat()) {

                    if (pendingMovement == null || precedingMovementBoard == null)
                        throw new IllegalArgumentException(
                                "Separate retreat snapshot has no pending movement result");

                    requireSamePosition(
                            precedingMovementBoard, phase.board(),
                            "Separate retreat snapshot must contain "
                                    + "the preceding movement's starting board");

                } else {

                    requireSamePosition(
                            game.board(), phase.board(),
                            "Source board differs from the locally replayed board");

                }

                // Do not resolve a phase without a following source boundary.
                if (index == phases.size() - 1)
                    break;

                DiploBNPhase next = phases.get(index + 1);

                if (phase.gamePhase().isMovement()) {

                    BoardState before = game.board();

                    var movementRecord = archive.resolveMovement(
                            movementOrders(phase, before.locations()),
                            resolutionMoment(game, sequence++));

                    MovementResult result = movementRecord.result();

                    verifyMovementResolutions(phase, before, result, notes);

                    boolean hasRetreatData = !phase.retreatOrders().isEmpty()
                            || !phase.retreatResolutions().isEmpty();

                    if (result.dislodgements().isEmpty()) {

                        if (hasRetreatData)
                            throw new IllegalArgumentException(
                                    "Source contains retreat data but local movement "
                                            + "produced no dislodgements");

                        pendingMovement = null;
                        precedingMovementBoard = null;

                    } else {

                        boolean separateRetreat =
                                momentOf(next).equals(currentMoment(game));

                        if (separateRetreat) {

                            if (hasRetreatData)
                                throw new IllegalArgumentException(
                                        "Retreats are present both inside movement "
                                                + "and in a separate snapshot");

                            pendingMovement = result;
                            precedingMovementBoard = before;

                        } else {

                            if (!hasRetreatData)
                                throw new IllegalArgumentException(
                                        "Local dislodgements require retreats, but neither "
                                                + "embedded retreat evidence nor a separate "
                                                + "retreat snapshot is available");

                            requireEmbeddedRetreatCoverage(phase, result);

                            GameMoment retreatMoment = currentMoment(game);

                            resolveRetreat(
                                    archive, phase, result,
                                    resolutionMoment(game, sequence++),
                                    next, notes);

                            notes.add(retreatMoment
                                    + ": expanded embedded source retreat submissions");

                            pendingMovement = null;
                            precedingMovementBoard = null;

                        }

                    }

                } else if (phase.gamePhase().isRetreat()) {

                    resolveRetreat(
                            archive, phase, pendingMovement,
                            resolutionMoment(game, sequence++),
                            next, notes);

                    pendingMovement = null;
                    precedingMovementBoard = null;

                } else {

                    if (!phase.retreatOrders().isEmpty()
                            || !phase.retreatResolutions().isEmpty())
                        throw new IllegalArgumentException(
                                "Unexpected retreat data in a Winter snapshot");

                    BoardState before = game.board();

                    var adjustmentRecord = archive.resolveAdjustments(
                            adjustmentOrders(phase, before.locations()),
                            resolutionMoment(game, sequence++));

                    Map<String, Boolean> results = new HashMap<>();

                    for (var outcome : adjustmentRecord.result().outcomes()) {

                        AdjustmentOrder order = outcome.order();
                        Province location;

                        if (order instanceof BuildOrder build)
                            location = build.location();
                        else if (order instanceof DisbandOrder disband)
                            location = before.locationOf(disband.unit());
                        else
                            continue;

                        putResult(
                                results, order.owner(), location, outcome.accepted());

                    }

                    verifyResolutions(phase.resolutions(), results, false);

                }

            } catch (IllegalArgumentException | IllegalStateException exception) {

                throw new IllegalArgumentException(
                        "DiploBN/" + sourceGameId
                                + ", snapshot " + index
                                + ", " + sourceMoment
                                + ": " + exception.getMessage(),
                        exception);

            }

        }

        return new ImportResult(archive.build(), notes);

    }


    public static UUID archiveId(long sourceGameId) {

        if (sourceGameId < 1)
            throw new IllegalArgumentException("DiploBN GameID must be positive");

        // Keep source identity stable across importer revisions.
        return UUID.nameUUIDFromBytes(
                ("DIPLOBN:" + sourceGameId).getBytes(StandardCharsets.UTF_8));

    }

    private static GameMoment momentOf(DiploBNPhase phase) {
        return new GameMoment(phase.sourcePhase() / 10, phase.gamePhase());
    }

    private static GameMoment currentMoment(Game game) {
        return new GameMoment(game.year(), game.phase());
    }

    private static Moment resolutionMoment(Game game, long sequence) {

        // Synthetic ordering timestamps, not historical resolution times.
        return new Moment(
                game.year(), game.phase(), Instant.EPOCH.plusSeconds(sequence));

    }


    // Movement submissions preserve the original instruction. \\

    private static List<Order> movementOrders(
            DiploBNPhase phase, Map<UnitId, Province> active) {

        List<Order> result = new ArrayList<>();

        for (Order source : phase.movementOrders()) {

            UnitId unit = rebind(source.unit(), phase.board(), active);

            result.add(new Order(
                    unit, source.orderType(),
                    source.target(), source.auxiliaryTarget()));

        }

        return result;

    }


    // Retreats may be embedded in movement or separately archived. \\

    private static void requireEmbeddedRetreatCoverage(
            DiploBNPhase phase, MovementResult movement) {

        Set<String> expected = new HashSet<>();

        for (var entry : movement.dislodgements().entrySet())
            expected.add(resultKey(
                    entry.getKey().owner(), entry.getValue().displacedFrom()));

        Set<String> observed = new HashSet<>();

        for (RetreatOrder order : phase.retreatOrders())
            observed.add(resultKey(
                    order.owner(), phase.board().locationOf(order.unit())));

        // The current parser drops explicit retreat-disband orders.
        // Their result annotations still provide evidence of a submission.
        for (DiploBNOrderResolution resolution : phase.retreatResolutions()) {

            if (!resolution.retreatOrder())
                throw new IllegalArgumentException("Non-retreat annotation in retreat results");

            observed.add(resultKey(
                    resolution.nation(), resolution.issuingProvince()));

        }

        if (!observed.equals(expected))
            throw new IllegalArgumentException(
                    "Embedded retreat evidence does not cover exactly local dislodgements"
                            + "; expected=" + new TreeSet<>(expected)
                            + "; observed=" + new TreeSet<>(observed));

    }

    private static void resolveRetreat(
            GameRecordBuilder archive,
            DiploBNPhase phase,
            MovementResult movement,
            Moment resolvedAt,
            DiploBNPhase next,
            List<String> notes) {

        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(notes, "notes");

        Map<UnitId, Province> retreating = new LinkedHashMap<>();

        for (var entry : movement.dislodgements().entrySet())
            retreating.put(
                    entry.getKey(),
                    entry.getValue().displacedFrom());

        List<RetreatOrder> orders = new ArrayList<>();
        Map<UnitId, RetreatOrder> submittedMoves = new LinkedHashMap<>();

        for (RetreatOrder source : phase.retreatOrders()) {

            UnitId unit = rebind(
                    source.unit(),
                    phase.board(),
                    retreating);

            RetreatOrder rebound =
                    new RetreatOrder(unit, source.destination());

            if (submittedMoves.putIfAbsent(unit, rebound) != null)
                throw new IllegalArgumentException(
                        "Duplicate retreat submission for " + unit);

            orders.add(rebound);

        }

        var record = archive.resolveRetreats(orders, resolvedAt);

        Map<String, UnitId> issuers = new HashMap<>();

        for (var entry : retreating.entrySet()) {

            String key = resultKey(
                    entry.getKey().owner(), entry.getValue());

            if (issuers.putIfAbsent(key, entry.getKey()) != null)
                throw new IllegalArgumentException(
                        "RETREAT_RESULT: ambiguous dislodged unit for " + key);

        }

        Set<String> seen = new HashSet<>();
        List<String> pendingNotes = new ArrayList<>();

        for (DiploBNOrderResolution annotation : phase.retreatResolutions()) {

            if (!annotation.retreatOrder())
                throw new IllegalArgumentException(
                        "RETREAT_RESULT: source annotation has the wrong phase type");

            String key = resultKey(
                    annotation.nation(),
                    annotation.issuingProvince());

            if (!seen.add(key))
                throw new IllegalArgumentException(
                        "RETREAT_RESULT: duplicate source result for " + key);

            UnitId unit = issuers.get(key);

            if (unit == null)
                throw new IllegalArgumentException(
                        "RETREAT_RESULT: source result names a unit "
                                + "not locally dislodged: " + key);

            var outcome = record.result().outcomeOf(unit);

            if (outcome == null)
                throw new IllegalStateException(
                        "RETREAT_RESULT: missing local outcome for " + key);

            RetreatOrder submitted = submittedMoves.get(unit);

            /*
             * The current parser drops explicit disband instructions.
             * With no parsed retreat move, this boolean describes destruction,
             * not proof that an explicit human disband was submitted.
             */
            boolean localComparedValue = submitted != null
                    ? outcome.succeeded()
                    : record.result().destroyedUnits().contains(unit);

            if (annotation.successful() == null
                    || annotation.successful() == localComparedValue)
                continue;

            String detail =
                    key
                            + "; submission="
                            + (submitted == null
                            ? "NO_PARSED_RETREAT_MOVE"
                            : "MOVE_TO_" + submitted.destination().name())
                            + "; localStatus=" + outcome.status()
                            + "; localDestination=" + outcome.destination()
                            + "; source=" + annotation.successful()
                            + "; localComparedValue=" + localComparedValue
                            + "; reason=" + annotation.reason();

            /*
             * Narrow compatibility rule demonstrated by the supplied exports:
             * Completed snapshot, negative annotation without a reason,
             * and a locally successful retreat or destruction.
             *
             * This is only a candidate exception here. It is not accepted
             * until the complete next source position has been checked below.
             */
            boolean candidateException =
                    phase.sourceStatus().equals("Completed")
                            && Boolean.FALSE.equals(annotation.successful())
                            && annotation.reason() == null
                            && localComparedValue;

            if (!candidateException)
                throw new IllegalArgumentException(
                        "RETREAT_RESULT: disagreement for " + detail);

            pendingNotes.add(
                    resolvedAt.gameMoment()
                            + ": RETREAT_ANNOTATION_CONTRADICTS_NEXT_POSITION; "
                            + detail);

        }

        if (!pendingNotes.isEmpty()) {

            requireFollowingRetreatBoundary(
                    resolvedAt,
                    record.boardAfter(),
                    next);

            // Publish compatibility notes only after position verification.
            notes.addAll(pendingNotes);

        }

    }


    // Adjustment submissions and omitted Winter safeguards. \\

    private static List<AdjustmentOrder> adjustmentOrders(
            DiploBNPhase phase, Map<UnitId, Province> active) {

        List<AdjustmentOrder> result = new ArrayList<>();

        for (AdjustmentOrder source : phase.adjustmentOrders()) {

            if (source instanceof BuildOrder build) {

                result.add(new BuildOrder(
                        build.owner(), build.unitType(), build.location()));

            } else if (source instanceof DisbandOrder disband) {

                result.add(new DisbandOrder(
                        disband.owner(),
                        rebind(disband.unit(), phase.board(), active)));

            } else if (source instanceof WaiveOrder waive) {

                result.add(new WaiveOrder(waive.owner()));

            } else {

                throw new IllegalArgumentException(
                        "Unsupported adjustment submission: "
                                + source.getClass().getName());

            }

        }

        return result;

    }

    private static void requireNoMandatoryDisbands(BoardState board) {

        Map<Nation, Integer> units = new EnumMap<>(Nation.class);
        Map<Nation, Integer> centers = new EnumMap<>(Nation.class);

        for (UnitId unit : board.locations().keySet())
            units.merge(unit.owner(), 1, Integer::sum);

        for (Nation owner : board.owners().values())
            centers.merge(owner, 1, Integer::sum);

        for (Nation nation : Nation.values())
            if (units.getOrDefault(nation, 0) > centers.getOrDefault(nation, 0))
                throw new IllegalArgumentException(
                        "Cannot reconstruct omitted Winter with mandatory disbands for "
                                + nation);

    }


    // Persistent identity rebinding uses actual location and exact coast. \\

    private static UnitId rebind(
            UnitId sourceUnit, BoardState sourceBoard,
            Map<UnitId, Province> candidates) {

        Province location = sourceBoard.locationOf(sourceUnit);

        if (location == null)
            throw new IllegalArgumentException(
                    "Source issuer is absent from its snapshot");

        UnitId match = null;

        for (var entry : candidates.entrySet()) {

            UnitId unit = entry.getKey();

            if (unit.owner() != sourceUnit.owner()
                    || unit.unitType() != sourceUnit.unitType()
                    || entry.getValue() != location)
                continue;

            if (match != null)
                throw new IllegalArgumentException(
                        "Ambiguous continuing unit at " + location);

            match = unit;

        }

        if (match == null)
            throw new IllegalArgumentException(
                    "No continuing " + sourceUnit.owner() + " "
                            + sourceUnit.unitType() + " at " + location);

        return match;

    }


    private static void requireSamePosition(
            BoardState expected, BoardState actual, String message) {

        Map<Province, String> expectedUnits = position(expected);
        Map<Province, String> actualUnits = position(actual);

        List<String> unitDifferences = new ArrayList<>();
        List<String> ownerDifferences = new ArrayList<>();

        for (Province province : Province.values()) {

            String localUnit = expectedUnits.get(province);
            String sourceUnit = actualUnits.get(province);

            if (!Objects.equals(localUnit, sourceUnit))
                unitDifferences.add(
                        province.name()
                                + ": local="
                                + (localUnit == null ? "<empty>" : localUnit)
                                + ", source="
                                + (sourceUnit == null ? "<empty>" : sourceUnit));

            Nation localOwner = expected.owners().get(province);
            Nation sourceOwner = actual.owners().get(province);

            if (!Objects.equals(localOwner, sourceOwner))
                ownerDifferences.add(
                        province.name()
                                + ": local=" + localOwner
                                + ", source=" + sourceOwner);

        }

        if (!unitDifferences.isEmpty() || !ownerDifferences.isEmpty())
            throw new IllegalArgumentException(
                    message
                            + "; unitDifferences=" + unitDifferences
                            + "; ownerDifferences=" + ownerDifferences);

    }

    private static Map<Province, String> position(BoardState board) {

        Map<Province, String> result = new EnumMap<>(Province.class);

        for (var entry : board.locations().entrySet()) {

            UnitId unit = entry.getKey();

            String previous = result.put(
                    entry.getValue(),
                    unit.owner().name() + "|" + unit.unitType().name());

            if (previous != null)
                throw new IllegalArgumentException(
                        "Multiple units at " + entry.getValue());

        }

        return result;

    }


    // Annotation comparison: never replace the local adjudication result. \\

    private static void verifyMovementResolutions(
            DiploBNPhase phase,
            BoardState before,
            MovementResult result,
            List<String> notes) {

        Map<String, UnitId> issuers = new HashMap<>();

        for (var entry : result.outcomes().entrySet()) {

            if (!entry.getValue().submitted())
                continue;

            String key = resultKey(
                    entry.getKey().owner(), before.locationOf(entry.getKey()));

            if (issuers.putIfAbsent(key, entry.getKey()) != null)
                throw new IllegalArgumentException("Duplicate local issuer: " + key);

        }

        Set<String> seen = new HashSet<>();
        DiploBNAdjudicationComparator comparison = null;

        for (DiploBNOrderResolution source : phase.resolutions()) {

            if (source.retreatOrder())
                throw new IllegalArgumentException(
                        "Retreat annotation found in movement results");

            String key = resultKey(source.nation(), source.issuingProvince());

            if (!seen.add(key))
                throw new IllegalArgumentException("Duplicate source result: " + key);

            UnitId unit = issuers.get(key);

            if (unit == null)
                throw new IllegalArgumentException(
                        "Source result has no local submission: " + key);

            MovementResult.Outcome local = result.outcomes().get(unit);

            if (source.successful() == null
                    || source.successful() == local.succeeded())
                continue;

            // Classify only when there is a disagreement.
            if (comparison == null)
                comparison = DiploBNAdjudicationComparator.compare(phase);

            DiploBNAdjudicationComparator.Entry classified = null;

            for (var candidate : comparison.entries()) {

                var order = candidate.adjudicatedOrder();

                if (order.owner == unit.owner()
                        && order.pos0 == before.locationOf(unit)) {

                    if (classified != null)
                        throw new IllegalArgumentException(
                                "Ambiguous comparator result for " + key);

                    classified = candidate;

                }

            }

            if (classified == null
                    || !sameLocalOrder(classified.adjudicatedOrder(), local,
                    before.locationOf(unit))
                    || !annotationOnly(classified.difference()))
                throw new IllegalArgumentException(
                        "Source result disagrees with local adjudication for " + key
                                + "; source=" + source.successful()
                                + "; local=" + local.succeeded()
                                + "; reason=" + source.reason()
                                + "; category="
                                + (classified == null ? "UNCLASSIFIED" : classified.difference()));

            notes.add(momentOf(phase) + ": annotation difference for " + key
                    + "; category=" + classified.difference()
                    + "; source=" + source.successful()
                    + "; local=" + local.succeeded());

        }

    }

    private static void requireFollowingRetreatBoundary(
            Moment retreat,
            BoardState localAfterRetreat,
            DiploBNPhase next) {

        int year = retreat.year();

        GameMoment expected = switch (retreat.gamePhase()) {

            case SPRING_RETREAT ->
                    new GameMoment(year, GamePhase.FALL_MOVEMENT);

            case FALL_RETREAT ->
                    new GameMoment(year, GamePhase.WINTER_ADJUSTMENT);

            default -> throw new IllegalArgumentException(
                    "Retreat boundary verification requires a retreat phase");

        };

        GameMoment actual = momentOf(next);

        boolean omittedWinter =
                retreat.gamePhase() == GamePhase.FALL_RETREAT
                        && actual.equals(new GameMoment(
                        year + 1, GamePhase.SPRING_MOVEMENT));

        if (!actual.equals(expected) && !omittedWinter)
            throw new IllegalArgumentException(
                    "RETREAT_RESULT: cannot verify annotation exception "
                            + "against a nonconsecutive boundary"
                            + "; expected=" + expected
                            + "; found=" + actual);

        /*
         * Do not use a later board to justify a retreat if an intervening
         * mandatory adjustment could have removed units.
         */
        if (omittedWinter)
            requireNoMandatoryDisbands(localAfterRetreat);

        requireSamePosition(
                localAfterRetreat,
                next.board(),
                "RETREAT_RESULT: annotation exception rejected because "
                        + "the complete following source board does not match");

    }

    private static boolean sameLocalOrder(
            adjudication.Order compared,
            MovementResult.Outcome local,
            Province origin) {

        Order submitted = local.submittedOrder();

        // Do not apply exemptions from an independently transformed result.
        return !local.rewrittenBySzykman()
                && local.effectiveType() == submitted.orderType()
                && compared.resolved
                && !compared.visited
                && compared.getSnapshot() == null
                && compared.owner == submitted.owner()
                && compared.unitType == submitted.unitType()
                && compared.pos0 == origin
                && compared.orderType == submitted.orderType()
                && compared.pos1 == submitted.target()
                && compared.pos2 == submitted.auxiliaryTarget()
                && compared.verdict == local.succeeded();

    }

    private static boolean annotationOnly(
            DiploBNAdjudicationComparator.Difference difference) {

        return switch (difference) {
            case INEFFECTIVE_SUPPORT,
                 INEFFECTIVE_CONVOY,
                 SUPPORT_FOR_INVALID_MOVE,
                 SUPPORT_TO_OWN_PROVINCE,
                 CONVOY_FOR_FAILED_MOVE,
                 INVALID_CONVOY_DESTINATION -> true;

            // In particular, coast ambiguity and invalid-move hold-support
            // policy differences are NOT accepted here.
            default -> false;
        };

    }


    private static void putResult(
            Map<String, Boolean> results,
            Nation nation, Province location, boolean successful) {

        String key = resultKey(nation, location);

        if (results.putIfAbsent(key, successful) != null)
            throw new IllegalArgumentException(
                    "Cannot unambiguously match multiple results for " + key);

    }

    private static void verifyResolutions(
            List<DiploBNOrderResolution> source,
            Map<String, Boolean> local,
            boolean retreat) {

        String stage = retreat ? "RETREAT_RESULT" : "ADJUSTMENT_RESULT";
        Set<String> seen = new HashSet<>();

        for (DiploBNOrderResolution resolution : source) {

            if (resolution.retreatOrder() != retreat)
                throw new IllegalArgumentException(
                        stage + ": source result has the wrong phase type");

            String key = resultKey(
                    resolution.nation(), resolution.issuingProvince());

            if (!seen.add(key))
                throw new IllegalArgumentException(
                        stage + ": duplicate source result for " + key);

            Boolean actual = local.get(key);

            if (actual == null)
                throw new IllegalArgumentException(
                        stage + ": source result has no matching local result for "
                                + key);

            if (resolution.successful() != null
                    && !resolution.successful().equals(actual))
                throw new IllegalArgumentException(
                        stage + ": source result disagrees with local adjudication for "
                                + key
                                + "; source=" + resolution.successful()
                                + "; local=" + actual
                                + "; reason=" + resolution.reason());

        }

    }

    private static String resultKey(Nation nation, Province location) {

        Objects.requireNonNull(location, "result location");

        return nation.name() + "|" + Province.canonical(location).name();

    }

}