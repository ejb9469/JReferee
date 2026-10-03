package testing;

import analysis.*;
import domain.*;
import game.BoardState;
import phase.*;

import java.util.*;
import static testing.BackendDiagnosticsSelfCheck.*;

/** Deterministic, corpus-free checks of descriptive findings and opt-in bias. */
public final class TacticalBiasSelfCheck {
    private TacticalBiasSelfCheck() { }

    public static void main(String[] args) {
        detection();
        rankingAndComparison();
        beam();
        boundedBiasHonesty();
        configurationConsistency();
        compatibilityAndValidation();
        System.out.println("Tactical bias checks passed.");
    }

    private static OrderPlan plan(Order... orders) {
        return new OrderPlan(Nation.ENGLAND, List.of(orders), 0);
    }

    private static UnitId own(UnitType type, Province province) {
        return unit(Nation.ENGLAND, type, province);
    }

    private static void detection() {
        UnitId edi = own(UnitType.FLEET, Province.Edi);
        UnitId nth = own(UnitType.FLEET, Province.NTH);
        UnitId yor = own(UnitType.ARMY, Province.Yor);
        BoardState board = board(edi, nth, yor);
        Order move = Order.move(edi, Province.NTH);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Hol);
        Order army = Order.move(yor, Province.Hol);
        var findings = TacticalBias.inspect(board, plan(move, convoy, army));
        require(findings.size() == 1 && findings.getFirst().category()
                == TacticalPrinciple.Category.FRIENDLY_CONVOY_FLEET, "Convoy finding missing");
        require(findings.getFirst().penalty() == 0 && !findings.getFirst().rejected(),
                "Descriptive warning carries a penalty or rejection");
        require(findings.equals(TacticalAnalysis.FRIENDLY_CONVOY_FLEET
                .inspect(board, plan(move, convoy, army))), "Legacy convoy principle changed");
        require(findings.equals(TacticalBias.inspect(board, plan(army, convoy, move))),
                "Finding order depends on submitted order order");
        immutable(() -> findings.clear());
        require(TacticalBias.offendingMoveCount(board, plan(move)) == 0,
                "Unresolved partial target counted");
        require(TacticalBias.offendingMoveCount(board, plan(move, Order.hold(nth))) == 1,
                "Friendly HOLD collision was not counted");
        var heldFinding = TacticalBias.inspect(board, plan(move, Order.hold(nth))).getFirst();
        require(heldFinding.category() == TacticalPrinciple.Category.FRIENDLY_HOLD_UNIT
                        && heldFinding.explanation().equals("Moves into your holding unit.")
                        && heldFinding.suggestedAlternative().orderType() == OrderType.SUPPORT,
                "Friendly HOLD collision missing or not concise");
        require(TacticalBias.offendingMoveCount(board,
                plan(move, Order.move(nth, Province.Nwy))) == 0, "Moving target counted");
        require(TacticalBias.offendingMoveCount(board,
                plan(Order.supportHold(edi, Province.NTH), convoy, army)) == 0,
                "Non-MOVE counted");
        UnitId foreign = unit(Nation.FRANCE, UnitType.FLEET, Province.NTH);
        require(TacticalBias.offendingMoveCount(board(edi, foreign), plan(move)) == 0,
                "Foreign occupancy counted without selected own order");
        UnitId mover = own(UnitType.ARMY, Province.Bur);
        UnitId supporter = own(UnitType.FLEET, Province.Lon);
        UnitId supporterTwo = own(UnitType.FLEET, Province.Bre);
        UnitId supportedForeign = new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                Nation.FRANCE, UnitType.ARMY, Province.Edi);
        BoardState supportedBoard = new BoardState(Map.of(mover, Province.Bur, supporter, Province.Lon,
                supporterTwo, Province.Bre, supportedForeign, Province.Pic), Map.of());
        Order incoming = Order.move(mover, Province.Bel);
        Order supportForeign = Order.supportMove(supporter, Province.Pic, Province.Bel);
        Order supportForeignTwo = Order.supportMove(supporterTwo, Province.Pic, Province.Bel);
        var supportedFindings = TacticalBias.inspect(supportedBoard,
                plan(incoming, supportForeign, supportForeignTwo));
        require(supportedFindings.size() == 2 && supportedFindings.stream().allMatch(warning ->
                        warning.category() == TacticalPrinciple.Category.FOREIGN_SUPPORTED_DESTINATION
                                && warning.explanation().equals("Competes with a foreign move you support.")
                                && warning.suggestedAlternative() == null)
                        && TacticalBias.offendingMoveCount(supportedBoard,
                        plan(incoming, supportForeign, supportForeignTwo)) == 1,
                "Multiple own supports did not report all reasons while counting the MOVE once");
        require(supportedFindings.equals(TacticalBias.inspect(supportedBoard,
                        plan(supportForeignTwo, incoming, supportForeign))),
                "Foreign-destination warning signatures depend on order insertion");
        require(TacticalBias.offendingMoveCount(supportedBoard,
                plan(Order.move(mover, Province.Pic), supportForeign)) == 0,
                "Move into supported foreign unit's origin was flagged");
        require(TacticalBias.offendingMoveCount(supportedBoard,
                plan(incoming, Order.supportHold(supporter, Province.Pic))) == 0,
                "Foreign support-hold created a destination conflict");
        require(TacticalBias.offendingMoveCount(supportedBoard,
                plan(incoming, Order.supportMove(supporter, Province.Pic, Province.Por))) == 0,
                "Different supported destination was flagged");
        rejects(() -> plan(incoming, Order.supportMove(
                unit(Nation.FRANCE, UnitType.FLEET, Province.MAO), Province.Pic, Province.Bel)));
        require(TacticalBias.offendingMoveCount(supportedBoard,
                plan(incoming, Order.supportMove(supporter, Province.NTH, Province.Bel))) == 0,
                "Missing foreign support reference created a conflict");
        require(TacticalBias.offendingMoveCount(supportedBoard,
                plan(incoming, new Order(supporter, OrderType.SUPPORT, Province.Edi, Province.Bel))) == 0,
                "Support reference to a unit's creation location was accepted");
        BoardState missingReferenceBoard = board(mover, supporter);
        Order missingSupport = Order.supportMove(supporter, Province.Pic, Province.Bel);
        OrderPlan missingReferencePlan = plan(incoming, missingSupport);
        require(TacticalBias.inspect(missingReferenceBoard, missingReferencePlan).isEmpty()
                        && CoordinatedOrders.coordinationProblem(missingReferenceBoard,
                        missingReferencePlan).isPresent(),
                "Missing foreign support reference fabricated a warning or bypassed rejection: "
                        + CoordinatedOrders.coordinationProblem(missingReferenceBoard, missingReferencePlan));
        UnitId foreignCoast = new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                Nation.FRANCE, UnitType.FLEET, Province.Lon);
        BoardState coastSupportBoard = new BoardState(Map.of(mover, Province.MAO,
                supporter, Province.Por, foreignCoast, Province.SpaNC), Map.of());
        require(TacticalBias.offendingMoveCount(coastSupportBoard, plan(
                Order.move(mover, Province.SpaSC),
                Order.supportMove(supporter, Province.SpaNC, Province.Spa))) == 1,
                "Exact current foreign coast or canonical destination matching failed");
        require(TacticalBias.offendingMoveCount(coastSupportBoard, plan(
                Order.move(mover, Province.SpaSC),
                Order.supportMove(supporter, Province.SpaSC, Province.Spa))) == 0,
                "Canonicalized foreign support origin bypassed exact-coast matching");
        UnitId foreignArmy = unit(Nation.FRANCE, UnitType.ARMY, Province.Yor);
        BoardState foreignTargetBoard = board(edi, nth, foreignArmy);
        for (Order stationary : List.of(Order.convoy(nth, Province.Yor, Province.Hol),
                Order.supportHold(nth, Province.Yor),
                Order.supportMove(nth, Province.Yor, Province.Hol)))
            require(TacticalBias.offendingMoveCount(foreignTargetBoard, plan(move, stationary)) == 1,
                    "Own stationary order cooperating with foreign target was exempted");
        var foreignScenarios = Map.of(
                "foreign-convoy", List.of(Order.convoy(foreign, Province.Yor, Province.Hol),
                        Order.move(foreignArmy, Province.Hol)),
                "foreign-support", List.of(Order.supportMove(foreign, Province.Yor, Province.Hol),
                        Order.hold(foreignArmy)));
        var foreignEvaluation = new OutcomeEvaluator(0, 0, 0, 5).rank(
                board(edi, foreign, foreignArmy), moment(), List.of(plan(move)),
                foreignScenarios, Map.of(), processor()).getFirst();
        require(foreignEvaluation.findings().isEmpty() && foreignEvaluation.penaltyTotal() == 0,
                "Foreign scenario supporter/convoyer counted as own selected stationary order");
        require(TacticalBias.offendingMoveCount(board(edi),
                plan(move, convoy)) == 0, "Inactive selected target counted");
        UnitId lon = own(UnitType.FLEET, Province.Lon);
        var two = TacticalBias.inspect(board(edi, nth, yor, lon),
                plan(Order.move(lon, Province.NTH), convoy, move, army));
        require(two.size() == 2 && two.stream().map(TacticalPrinciple.Warning::id).distinct()
                .count() == 2, "Multiple offending MOVEs not independently counted");
        UnitId held = own(UnitType.ARMY, Province.Bel);
        UnitId validSupporter = own(UnitType.ARMY, Province.Ruh);
        BoardState overlappingBoard = new BoardState(Map.of(mover, Province.Bur, validSupporter, Province.Ruh,
                held, Province.Bel, supportedForeign, Province.Pic), Map.of());
        Order heldMove = Order.move(mover, Province.Bel);
        Order hold = Order.hold(held);
        Order foreignSupportToBel = Order.supportMove(validSupporter, Province.Pic, Province.Bel);
        var overlapping = TacticalBias.inspect(overlappingBoard,
                plan(heldMove, hold, foreignSupportToBel));
        require(overlapping.size() == 2
                        && TacticalBias.offendingMoveCount(overlappingBoard,
                        plan(heldMove, hold, foreignSupportToBel)) == 1,
                "Overlapping HOLD and foreign-destination reasons double-counted a MOVE");
        var overlapEvaluation = new OutcomeEvaluator(0, 0, 0, 3).rank(overlappingBoard, moment(),
                List.of(plan(heldMove, hold, foreignSupportToBel)),
                Map.of("quiet", List.of(Order.hold(supportedForeign))), Map.of(), processor()).getFirst();
        require(overlapEvaluation.findings().size() == 2 && overlapEvaluation.offendingMoveCount() == 1
                        && overlapEvaluation.penaltyTotal() == 3,
                "Plan metadata charged once per warning rather than once per incoming MOVE");
        Order replacementSupport = Order.supportHold(mover, Province.Bel);
        var overlapComparison = TacticalAnalysis.analyze(overlappingBoard, moment(),
                plan(heldMove, hold, foreignSupportToBel),
                predictions(heldMove, replacementSupport, hold, foreignSupportToBel),
                Map.of("quiet", List.of(Order.hold(supportedForeign))), Map.of(),
                new OutcomeEvaluator(0, 0, 0, 3), processor(), true, 8, 128)
                .comparisons().stream().filter(comparison -> comparison.warning().category()
                        == TacticalPrinciple.Category.FRIENDLY_HOLD_UNIT).findFirst().orElseThrow();
        require(overlapComparison.baselineEvaluation() != null,
                "Overlapping plan comparison was not evaluated: " + overlapComparison.diagnostic());
        require(overlapComparison.baselineEvaluation().offendingMoveCount() == 1
                        && overlapComparison.baselineEvaluation().penaltyTotal() == 3
                        && overlapComparison.alternativeEvaluation().offendingMoveCount() == 0
                        && overlapComparison.penaltyDelta() == -3
                        && overlapComparison.adjustedScoreDelta()
                        == overlapComparison.scoreDelta() - overlapComparison.penaltyDelta(),
                "Comparison duplicated overlapping warnings or changed raw/adjusted arithmetic");
        var nullAlternative = TacticalAnalysis.analyze(supportedBoard, moment(),
                plan(incoming, supportForeign), Map.of(), Map.of("quiet", List.of()), Map.of(),
                new OutcomeEvaluator(0, 0, 0, 3), processor(), true, 8, 128)
                .comparisons().getFirst();
        require(nullAlternative.warning().suggestedAlternative() == null
                        && nullAlternative.alternative() == null
                        && nullAlternative.warning().evaluatedStatus()
                        == TacticalPrinciple.EvaluationStatus.UNAVAILABLE,
                "Foreign-destination finding without an alternative is not safely unavailable");

        UnitId relocated = new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        UnitId mao = own(UnitType.FLEET, Province.MAO);
        UnitId por = own(UnitType.ARMY, Province.Por);
        BoardState coastBoard = new BoardState(Map.of(relocated, Province.SpaNC,
                mao, Province.MAO, por, Province.Por), Map.of());
        Order coastMove = Order.move(mao, Province.SpaSC);
        for (Order support : List.of(Order.supportHold(relocated, Province.Por),
                Order.supportMove(relocated, Province.Por, Province.Spa))) {
            var warning = TacticalBias.inspect(coastBoard, plan(coastMove, support)).getFirst();
            require(warning.category() == TacticalPrinciple.Category.FRIENDLY_SUPPORT_UNIT
                    && warning.principleId().equals("friendly-support-unit")
                    && warning.explanation().equals("Moves into your supporting unit.")
                    && warning.suggestedAlternative().target() == Province.SpaNC,
                    "Canonical support hold/move or current-location detection failed");
            require(TacticalAnalysis.FRIENDLY_CONVOY_FLEET
                    .inspect(coastBoard, plan(coastMove, support)).isEmpty(),
                    "Legacy convoy-only principle returned support finding");
        }
        UnitId replacement = new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        var renamed = TacticalBias.inspect(new BoardState(Map.of(replacement, Province.SpaNC,
                mao, Province.MAO), Map.of()),
                plan(coastMove, Order.supportHold(replacement, Province.Por))).getFirst();
        var original = TacticalBias.inspect(coastBoard,
                plan(coastMove, Order.supportHold(relocated, Province.Por))).getFirst();
        require(original.id().equals(renamed.id()), "Warning ID depends on UUID or origin");
        require(TacticalBias.offendingMoveCount(coastBoard,
                plan(Order.move(relocated, Province.SpaSC))) == 0, "Self MOVE counted");
        var advisory = TacticalAnalysis.analyze(coastBoard, moment(),
                plan(coastMove, Order.supportHold(relocated, Province.Por)), Map.of(),
                Map.of(), Map.of(), new OutcomeEvaluator(0, 0, 0), processor(), false, 8, 128);
        require(advisory.comparisons().size() == 1
                && advisory.comparisons().getFirst().warning().category()
                == TacticalPrinciple.Category.FRIENDLY_SUPPORT_UNIT,
                "Analysis does not include support category");
    }

    private static void rankingAndComparison() {
        UnitId edi = own(UnitType.FLEET, Province.Edi);
        UnitId nth = own(UnitType.FLEET, Province.NTH);
        UnitId yor = own(UnitType.ARMY, Province.Yor);
        BoardState board = board(edi, nth, yor);
        Order move = Order.move(edi, Province.NTH);
        Order support = Order.supportHold(edi, Province.NTH);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Hol);
        Order army = Order.move(yor, Province.Hol);
        OrderPlan flagged = new OrderPlan(Nation.ENGLAND, List.of(move, convoy, army), -0.1);
        OrderPlan safe = new OrderPlan(Nation.ENGLAND, List.of(support, convoy, army), -1);
        Map<String, List<Order>> scenarios = new LinkedHashMap<>();
        scenarios.put("quiet-b", List.of());
        scenarios.put("quiet-a", List.of());
        Map<Province, Double> objectives = Map.of(Province.Hol, 4.0);
        var raw = new OutcomeEvaluator(0, 0, 0.7).rank(board, moment(),
                List.of(safe, flagged), scenarios, objectives, processor());
        require(raw.equals(new OutcomeEvaluator(0, 0, 0.7, 0).rank(board, moment(),
                List.of(safe, flagged), scenarios, objectives, processor())),
                "Evaluator three-argument and explicit zero constructors differ");
        var biased = new OutcomeEvaluator(0, 0, 0.7, 3).rank(board, moment(),
                List.of(flagged, safe), scenarios, objectives, processor());
        require(raw.getFirst().plan().equals(flagged) && biased.getFirst().plan().equals(safe),
                "Equal-base convoy plan was not reranked");
        var oldFlagged = raw.getFirst();
        var newFlagged = biased.getLast();
        require(newFlagged.baseScore() == oldFlagged.score() && newFlagged.mean() == oldFlagged.mean()
                && newFlagged.worst() == oldFlagged.worst()
                && newFlagged.scenarioScores().equals(oldFlagged.scenarioScores())
                && newFlagged.scenarioDetails().equals(oldFlagged.scenarioDetails()),
                "Bias changed raw scenario outcomes");
        require(newFlagged.offendingMoveCount() == 1 && newFlagged.penaltyTotal() == 3
                && newFlagged.penaltyWeight() == 3 && newFlagged.score() == newFlagged.baseScore() - 3,
                "Penalty charged per scenario instead of once");
        require(oldFlagged.penaltyTotal() == 0 && oldFlagged.findings().size() == 1,
                "Zero weight lost descriptive findings");
        UnitId lon = own(UnitType.FLEET, Province.Lon);
        var twice = new OutcomeEvaluator(0, 0, 0.7, 3).rank(board(edi, nth, yor, lon), moment(),
                List.of(plan(move, convoy, army, Order.move(lon, Province.NTH))),
                scenarios, objectives, processor()).getFirst();
        require(twice.offendingMoveCount() == 2 && twice.penaltyTotal() == 6 && twice.score() == -2,
                "Penalty does not count each selected offending MOVE exactly once");
        require(new OutcomeEvaluator(0, 0, 0, 3).rank(board, moment(), List.of(flagged),
                scenarios, objectives, processor()).size() == 1, "Flagged-only plan rejected");
        var adjudicated = new JointOrders().evaluate(board, flagged, List.of(), processor());
        require(adjudicated.finalLocations().get(edi) == Province.Edi
                && adjudicated.finalLocations().get(yor) == Province.Hol
                && adjudicated.dislodgements().isEmpty(),
                "Bias-enabled self-bounce changed convoy adjudication");
        immutable(() -> newFlagged.findings().clear());
        var comparison = TacticalAnalysis.analyze(board, moment(), flagged,
                predictions(move, support, convoy, army), scenarios, objectives,
                new OutcomeEvaluator(0, 0, 0.7, 3), processor(), true, 8, 128)
                .comparisons().getFirst();
        require(comparison.scoreDelta() == 0 && comparison.adjustedScoreDelta() == 3
                && comparison.penaltyDelta() == -3 && comparison.meanDelta() == 0
                && comparison.worstDelta() == 0
                && comparison.scenarioDeltas().values().stream().allMatch(value -> value == 0),
                "Comparison raw/adjusted arithmetic inconsistent");
        var notCompared = TacticalAnalysis.analyze(board, moment(), flagged, Map.of(),
                scenarios, objectives, new OutcomeEvaluator(0, 0, 0, 3), processor(),
                false, 8, 128).comparisons().getFirst();
        require(notCompared.adjustedScoreDelta() == null && notCompared.penaltyDelta() == null,
                "Unevaluated comparison invented deltas");

        UnitId par = own(UnitType.ARMY, Province.Par);
        BoardState expanded = board(edi, nth, yor, par);
        var gain = plan(move, convoy, army, Order.move(par, Province.Bur));
        var noGain = plan(support, convoy, army, Order.hold(par));
        var outweighed = new OutcomeEvaluator(0, 0, 0, 3).rank(expanded, moment(),
                List.of(noGain, gain), Map.of("quiet", List.of()), Map.of(Province.Bur, 10.0),
                processor());
        require(outweighed.getFirst().plan().equals(gain) && outweighed.getFirst().score() == 7,
                "Finite penalty became an outcome-ranking veto");
    }

    private static void beam() {
        holdCollisionPair(Province.Bel, Province.Pic);
        holdCollisionPair(Province.Pic, Province.Bel);
        foreignDestinationPair(Province.Pic, Province.Bur);
        foreignDestinationPair(Province.Bur, Province.Hol);
        narrowPair(Province.Bel, Province.Pic);
        narrowPair(Province.Pic, Province.Bel);
        UnitId edi = own(UnitType.FLEET, Province.Edi);
        UnitId nth = own(UnitType.FLEET, Province.NTH);
        UnitId yor = own(UnitType.ARMY, Province.Yor);
        BoardState board = board(edi, nth, yor);
        Order move = Order.move(edi, Province.NTH);
        Order support = Order.supportHold(edi, Province.NTH);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Hol);
        Order army = Order.move(yor, Province.Hol);
        var evidence = weighted(predictions(move, support, convoy, army), move, 9);
        var biased = new CoordinatedOrders(2, 1, 0.001)
                .generate(board, Nation.ENGLAND, evidence, CoordinationMode.STRICT);
        require(biased.plans().size() == 1 && biased.plans().getFirst().orders().contains(support),
                "Narrow convoy beam pruned alternative before resolving mover-first dependency");
        require(biased.plans().getFirst().orders().getFirst().unit().equals(nth),
                "Potential stationary convoy target was not assigned first");
        require(biased.diagnostics().beamTruncated() && !biased.exhaustedRetainedDomains(),
                "Final bias beam cutoff was not reported honestly");
        require(new CoordinatedOrders(2, 1, 3).generate(board, Nation.ENGLAND,
                predictions(move, convoy, army), CoordinationMode.STRICT).plans().size() == 1,
                "Bias hard-rejected flagged-only retained domain");
        for (CoordinationMode mode : CoordinationMode.values()) {
            var legacy = new CoordinatedOrders(2, 8).generate(board, Nation.ENGLAND, evidence, mode);
            var explicitZero = new CoordinatedOrders(2, 8, 0)
                    .generate(board, Nation.ENGLAND, evidence, mode);
            require(legacy.equals(explicitZero), "Disabled beam changed legacy generation");
        }
        require(new CoordinatedOrders(2, 1, 3).generate(board, Nation.ENGLAND, evidence,
                CoordinationMode.RAW).equals(new CoordinatedOrders(2, 1).generate(board,
                Nation.ENGLAND, evidence, CoordinationMode.RAW)), "RAW generation changed");
        var mismatch = new CoordinatedOrders(2, 16, 3).generate(board, Nation.ENGLAND,
                predictions(move, convoy, Order.move(yor, Province.Nwy)), CoordinationMode.STRICT);
        require(mismatch.plans().isEmpty() && mismatch.diagnostics().rejectedCandidates() > 0,
                "Bias weakened hard coordination");
    }

    private static void holdCollisionPair(Province moverProvince, Province holderProvince) {
        UnitId mover = own(UnitType.ARMY, moverProvince);
        UnitId holder = own(UnitType.ARMY, holderProvince);
        BoardState board = board(mover, holder);
        Order incoming = Order.move(mover, holderProvince);
        Order holding = Order.hold(holder);
        Order movingAway = Order.move(holder,
                holderProvince == Province.Bel ? Province.Bur : Province.Bre);
        var evidence = weighted(predictions(incoming, holding, movingAway), holding, 9);
        var result = new CoordinatedOrders(2, 1, 1).generate(board, Nation.ENGLAND,
                evidence, CoordinationMode.STRICT);
        require(result.plans().size() == 1
                        && result.plans().getFirst().orders().contains(movingAway)
                        && TacticalBias.offendingMoveCount(board, result.plans().getFirst()) == 0,
                "Narrow beam pruned the safe moving-target choice for HOLD collision " + moverProvince);
        var flaggedOnly = new CoordinatedOrders(1, 1, 1).generate(board, Nation.ENGLAND,
                predictions(incoming, holding), CoordinationMode.STRICT);
        require(flaggedOnly.plans().size() == 1
                        && TacticalBias.offendingMoveCount(board, flaggedOnly.plans().getFirst()) == 1,
                "A flagged-only HOLD collision candidate was rejected");
    }

    private static void foreignDestinationPair(Province moverProvince, Province supporterProvince) {
        UnitId mover = own(UnitType.ARMY, moverProvince);
        UnitId supporter = own(UnitType.ARMY, supporterProvince);
        UnitId foreign = unit(Nation.FRANCE, UnitType.ARMY, Province.Ruh);
        BoardState board = board(mover, supporter, foreign);
        Order incoming = Order.move(mover, Province.Bel);
        Order support = Order.supportMove(supporter, Province.Ruh, Province.Bel);
        Order hold = Order.hold(supporter);
        var evidence = weighted(predictions(incoming, support, hold), support, 9);
        var result = new CoordinatedOrders(2, 1, 1).generate(board, Nation.ENGLAND,
                evidence, CoordinationMode.CONDITIONAL);
        require(!result.plans().isEmpty()
                        && result.plans().getFirst().orders().contains(hold)
                        && TacticalBias.offendingMoveCount(board, result.plans().getFirst()) == 0,
                "Narrow beam pruned the safe choice for supported foreign destination " + moverProvince
                        + ": " + result.plans() + "; " + result.diagnostics());
        var flaggedOnly = new CoordinatedOrders(1, 1, 1).generate(board, Nation.ENGLAND,
                predictions(incoming, support), CoordinationMode.CONDITIONAL);
        require(!flaggedOnly.plans().isEmpty()
                        && TacticalBias.offendingMoveCount(board, flaggedOnly.plans().getFirst()) == 1,
                "A flagged-only foreign-destination candidate was rejected");
    }

    private static void narrowPair(Province moverProvince, Province stationaryProvince) {
        UnitId mover = own(UnitType.ARMY, moverProvince);
        UnitId stationary = own(UnitType.ARMY, stationaryProvince);
        UnitId bur = own(UnitType.ARMY, Province.Bur);
        BoardState board = board(mover, stationary, bur);
        Order move = Order.move(mover, stationaryProvince);
        Order alternative = Order.supportHold(mover, stationaryProvince);
        Order stationarySupport = Order.supportHold(stationary, Province.Bur);
        var evidence = weighted(predictions(move, alternative, stationarySupport, Order.hold(bur)),
                move, 9);
        var result = new CoordinatedOrders(2, 1, 1).generate(board, Nation.ENGLAND,
                evidence, CoordinationMode.STRICT);
        require(result.plans().size() == 1 && result.plans().getFirst().orders().contains(alternative),
                "Narrow support beam failed assignment order " + moverProvince);
        require(result.plans().getFirst().orders().getFirst().unit().equals(stationary),
                "Potential stationary support target was not assigned first");
        var mixed = weighted(predictions(move, alternative, stationarySupport,
                Order.hold(stationary), Order.hold(bur)), stationarySupport, 9);
        mixed = weighted(mixed, move, 9);
        var robust = new CoordinatedOrders(2, 1, 1).generate(board, Nation.ENGLAND,
                mixed, CoordinationMode.STRICT);
        require(robust.plans().size() == 1
                && TacticalBias.offendingMoveCount(board, robust.plans().getFirst()) == 0,
                "Dependency alternatives lost before cutoff");
        var reverseMap = new LinkedHashMap<UnitId, RoutePrediction>();
        mixed.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing((UnitId unit) -> unit.value().toString()).reversed()))
                .forEach(entry -> reverseMap.put(entry.getKey(), entry.getValue()));
        require(robust.equals(new CoordinatedOrders(2, 1, 1).generate(board, Nation.ENGLAND,
                reverseMap, CoordinationMode.STRICT)), "Beam depends on evidence map order");
        Map<UnitId, Province> renamedLocations = new LinkedHashMap<>();
        Map<UnitId, RoutePrediction> renamedEvidence = new LinkedHashMap<>();
        Map<UnitId, UnitId> renamedUnits = new HashMap<>();
        for (UnitId original : List.of(mover, stationary, bur)) {
            UnitId renamed = new UnitId(UUID.nameUUIDFromBytes(("replacement:" + original.value())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), Nation.ENGLAND,
                    original.unitType(), Province.Yor);
            renamedUnits.put(original, renamed);
            renamedLocations.put(renamed, board.locationOf(original));
        }
        mixed.forEach((original, prediction) -> {
            Map<Order, Long> counts = new LinkedHashMap<>();
            prediction.counts().forEach((order, count) -> counts.put(new Order(renamedUnits.get(original),
                    order.orderType(), order.target(), order.auxiliaryTarget()), count));
            renamedEvidence.put(renamedUnits.get(original), new RoutePrediction("POSITION", 0, counts));
        });
        BoardState renamedBoard = new BoardState(renamedLocations, Map.of());
        var renamed = new CoordinatedOrders(2, 1, 1).generate(renamedBoard, Nation.ENGLAND,
                renamedEvidence, CoordinationMode.STRICT);
        require(renamed.plans().size() == 1 && analysis.openings.Classifier.signature(renamedBoard,
                renamed.plans().getFirst().orders()).equals(analysis.openings.Classifier.signature(
                board, robust.plans().getFirst().orders())), "Beam depends on UUID or origin");
    }

    private static Map<UnitId, RoutePrediction> weighted(Map<UnitId, RoutePrediction> evidence,
                                                        Order order, long count) {
        var result = new LinkedHashMap<>(evidence);
        var choices = new LinkedHashMap<>(result.get(order.unit()).counts());
        choices.put(order, count);
        result.put(order.unit(), new RoutePrediction("POSITION", 0, choices));
        return result;
    }

    private static void boundedBiasHonesty() {
        Province[] chain = {Province.Bre, Province.Pic, Province.Par, Province.Bur, Province.Mun,
                Province.Sil, Province.Boh, Province.Gal, Province.Ukr, Province.War};
        UnitId[] units = Arrays.stream(chain).map(province -> own(UnitType.ARMY, province))
                .toArray(UnitId[]::new);
        List<Order> orders = new ArrayList<>();
        for (int index = 0; index < units.length - 1; index++) {
            orders.add(Order.hold(units[index]));
            orders.add(Order.supportHold(units[index], chain[index + 1]));
        }
        orders.add(Order.supportMove(units[9], Province.Sil, Province.Pru));
        BoardState board = board(units);
        var evidence = predictions(orders.toArray(Order[]::new));
        var bounded = new CoordinatedOrders(2, 1, 5).generate(board, Nation.ENGLAND,
                evidence, CoordinationMode.STRICT);
        require(bounded.plans().isEmpty() && bounded.diagnostics().beamTruncated()
                && bounded.diagnostics().omittedChoices() == 0
                && bounded.diagnostics().expandedCandidates() == 1278
                && !bounded.exhaustedRetainedDomains(),
                "Internal 256 partial cutoff falsely claimed retained-domain exhaustion");
        var exhaustive = new CoordinatedOrders(2, 1024, 5).generate(board, Nation.ENGLAND,
                evidence, CoordinationMode.STRICT);
        require(exhaustive.plans().isEmpty() && !exhaustive.diagnostics().beamTruncated()
                && exhaustive.exhaustedRetainedDomains()
                && exhaustive.diagnostics().expandedCandidates() == 1534,
                "Bias-enabled exhaustive hard contradiction diagnostics changed");
    }

    private static void compatibilityAndValidation() {
        var configuration = new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL, 1, 2, 4, 8);
        var coordinated = new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL, 1, 2, 4, 8,
                CoordinationMode.STRICT);
        require(configuration.tacticalBiasWeight() == 0 && coordinated.tacticalBiasWeight() == 0,
                "Legacy configuration enabled bias");
        var dummy = plan(Order.hold(own(UnitType.ARMY, Province.Yor)));
        var five = new PlanEvaluation(dummy, Map.of("quiet", 2.0), 2, 2, 2);
        var six = new PlanEvaluation(dummy, Map.of("quiet", 2.0), 2, 2, 2, Map.of());
        require(five.equals(six) && five.score() == five.baseScore() && five.penaltyTotal() == 0
                && five.findings().isEmpty(), "Legacy evaluation constructor changed");
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            rejects(() -> TacticalBias.validateWeight(invalid));
            rejects(() -> new OutcomeEvaluator(0, 0, 0, invalid));
            rejects(() -> new OutcomeEvaluator(0, 0, 0).withTacticalBiasWeight(invalid));
            rejects(() -> new CoordinatedOrders(2, 4, invalid));
            rejects(() -> new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL,
                    1, 2, 4, 8, CoordinationMode.STRICT, invalid));
        }
        TacticalBias.validateWeight(0);
        TacticalBias.validateWeight(Double.MAX_VALUE);
        rejects(() -> new PlanEvaluation(dummy, Map.of("quiet", 2.0), 2, 2, 1,
                Map.of(), 2, 0, 0, 0, List.of()));
        UnitId edi = own(UnitType.FLEET, Province.Edi);
        UnitId nth = own(UnitType.FLEET, Province.NTH);
        UnitId lon = own(UnitType.FLEET, Province.Lon);
        UnitId yor = own(UnitType.ARMY, Province.Yor);
        rejects(() -> new OutcomeEvaluator(0, 0, 0, Double.MAX_VALUE).rank(
                board(edi, nth, lon, yor), moment(),
                List.of(plan(Order.move(edi, Province.NTH), Order.move(lon, Province.NTH),
                        Order.convoy(nth, Province.Yor, Province.Hol), Order.move(yor, Province.Hol))),
                Map.of("quiet", List.of()), Map.of(), processor()));
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid bias or arithmetic accepted");
    }

    private static void configurationConsistency() {
        BoardState starting = game.StandardGameFactory.create1901().board();
        UnitId nth = starting.locations().keySet().stream().filter(unit ->
                unit.owner() == Nation.ENGLAND && unit.origin() == Province.Edi).findFirst().orElseThrow();
        UnitId lon = starting.locations().keySet().stream().filter(unit ->
                unit.owner() == Nation.ENGLAND && unit.origin() == Province.Lon).findFirst().orElseThrow();
        UnitId yor = starting.locations().keySet().stream().filter(unit ->
                unit.owner() == Nation.ENGLAND && unit.origin() == Province.Lvp).findFirst().orElseThrow();
        game.Game training = new game.Game(1901, starting, processor(),
                new phase.retreats.RetreatProcessor(), new phase.adjustments.AdjustmentProcessor());
        var archive = new game.record.GameRecordBuilder(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), "tactical-bias", training);
        archive.resolveMovement(starting.locations().keySet().stream().map(unit ->
                unit.equals(nth) ? Order.move(unit, Province.NTH)
                        : unit.equals(yor) ? Order.move(unit, Province.Yor) : Order.hold(unit)).toList(),
                new game.Moment(1901,
                game.GamePhase.SPRING_MOVEMENT, java.time.Instant.EPOCH));
        BoardState board = training.board();
        OrderPlan flagged = plan(Order.move(lon, Province.NTH),
                Order.convoy(nth, Province.Yor, Province.Hol), Order.move(yor, Province.Hol));
        List<Order> fallOrders = new ArrayList<>(flagged.orders());
        board.locations().keySet().stream().filter(unit -> unit.owner() != Nation.ENGLAND)
                .map(Order::hold).forEach(fallOrders::add);
        archive.resolveMovement(fallOrders, new game.Moment(1901,
                game.GamePhase.FALL_MOVEMENT, java.time.Instant.ofEpochSecond(1)));
        RoutePreferences preferences = new RoutePreferences();
        require(preferences.add(archive.build()), "Synthetic tactical evidence not added");
        OutcomeEvaluator original = new OutcomeEvaluator(2, 3, 0.7);
        var configured = new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL, 1, 2, 4, 8,
                CoordinationMode.RAW, 5);
        var fall = new game.GameMoment(1901, game.GamePhase.FALL_MOVEMENT);
        rejects(() -> MovementStrategy.recommend(preferences, "tactical-bias", fall,
                board, Nation.ENGLAND, Map.of(), Map.of(Province.Hol, 4.0), configured,
                original, processor()));
        var recommendation = MovementStrategy.recommend(preferences, "tactical-bias", fall,
                board, Nation.ENGLAND, Map.of(), Map.of(Province.Hol, 4.0), configured,
                original.withTacticalBiasWeight(5), processor());
        var evaluation = recommendation.rankedPlans().getFirst();
        require(evaluation.penaltyWeight() == 5 && evaluation.penaltyTotal() == 5
                && evaluation.baseScore() == 6 && evaluation.score() == 1
                && original.tacticalBiasWeight() == 0,
                "Matching bias settings changed raw outcome settings or caller evaluator");
        rejects(() -> MovementStrategy.recommend(preferences, "tactical-bias", fall, board,
                Nation.ENGLAND, Map.of(), Map.of(Province.Hol, 4.0),
                new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL, 1, 2, 4, 8),
                original.withTacticalBiasWeight(5), processor()));
        var disabled = MovementStrategy.recommend(preferences, "tactical-bias", fall, board,
                Nation.ENGLAND, Map.of(), Map.of(Province.Hol, 4.0),
                new MovementStrategy.Configuration(PredictionPolicy.ORIGINAL, 1, 2, 4, 8,
                        CoordinationMode.RAW, -0.0),
                original, processor()).rankedPlans().getFirst();
        require(disabled.penaltyWeight() == 0 && disabled.penaltyTotal() == 0 && disabled.score() == 6
                && disabled.scenarioDetails().equals(evaluation.scenarioDetails()),
                "Signed zero weights rejected or disabled behavior changed");
        require(original.withTacticalBiasWeight(0) == original,
                "Unchanged evaluator settings unnecessarily copied");
    }
}
