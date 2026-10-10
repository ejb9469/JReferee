package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.assessment.*;
import analysis.tactics.detective.*;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Standalone baseline support-assessment checks.
 *
 * <p>Run without -ea. These are finite regression checks, not a proof of
 * correctness for every board, adjudication policy, or paradox.</p>
 */
public final class SupportAssessmentSelfCheck {


    // State \\

    private static int checks;


    // Construction \\

    private SupportAssessmentSelfCheck() {  }


    // Entry point \\

    public static void main(String[] args) {

        ProcessorMovementResolver resolver = resolver("selfcheck-engine");

        configurationChecks(resolver);
        supportedMove(resolver);
        rejectedSupportWithSuccessfulMove(resolver);
        rejectedSupportWithSurvival(resolver);
        dislodgedRecipient(resolver);
        unknownAndCompatibilityChecks(resolver);
        forgedMatchChecks(resolver);
        mismatchedResultCheck(resolver);

        System.out.println(
                "SupportAssessmentSelfCheck passed: " + checks + " checks");

    }


    // Configuration \\

    private static ProcessorMovementResolver resolver(String revision) {
        return new ProcessorMovementResolver(
                MovementProcessor.Policy.SZYKMAN_JUSTICE,
                4,
                0L,
                revision);
    }

    private static void configurationChecks(
            ProcessorMovementResolver resolver
    ) {

        require(
                resolver.rulesetId().equals(
                        resolver("selfcheck-engine").rulesetId()),
                "Same configuration must retain its identity");

        require(
                !resolver.rulesetId().equals(
                        resolver("different-engine").rulesetId()),
                "Engine revision must affect identity");

        expect(IllegalArgumentException.class, () ->
                new ProcessorMovementResolver(
                        MovementProcessor.Policy.SZYKMAN_JUSTICE,
                        0,
                        0L,
                        "revision"));

        expect(IllegalArgumentException.class, () ->
                new ProcessorMovementResolver(
                        MovementProcessor.Policy.SZYKMAN_JUSTICE,
                        1,
                        0L,
                        " "));

    }


    // Positive support-to-move \\

    private static void supportedMove(ProcessorMovementResolver resolver) {

        // Creation origin deliberately differs from current board location.
        UnitId mover = army(Nation.FRANCE, Province.Mar);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);

        Map<UnitId, Province> locations = Map.of(
                mover, Province.Par,
                supporter, Province.Bur);

        TacticalContext context = context(
                resolver,
                locations,
                Order.move(mover, Province.Pic),
                Order.supportMove(supporter, Province.Par, Province.Pic));

        TacticalScenario scenario = new TacticalScenario("supported-move", context);

        TacticMatch match = only(
                new SupportToMoveDetective().investigate(context));

        TacticalAssessment assessment = new SupportToMoveAssessor()
                .assess(match, scenario, resolver);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Truth.TRUE);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORTED_MOVE_SUCCEEDED,
                TacticalAssessment.Truth.TRUE);

        Map<UnitId, TacticalContext.KnownOrder> original =
                new LinkedHashMap<>(context.orders());

        MovementResult first = resolver.resolve(scenario);
        MovementResult second = resolver.resolve(scenario);

        require(first != second, "Each resolution returns a fresh result");

        require(
                first.outcomes().equals(second.outcomes()),
                "Repeated configured resolutions must agree on fixture outcomes");

        require(
                first.finalLocations().get(mover) == Province.Pic,
                "Use actual Par location, not creation origin Mar");

        require(
                context.board().locationOf(mover) == Province.Par,
                "Resolution must not advance the source board");

        require(
                original.equals(context.orders()),
                "Resolution must not mutate known evidence");

        expect(UnsupportedOperationException.class,
                () -> first.outcomes().clear());

        expect(UnsupportedOperationException.class,
                () -> first.finalLocations().clear());

        require(
                assessment.scenario() == scenario,
                "Assessment retains supplied scenario");

        for (TacticalAssessment.Finding finding : assessment.findings())
            for (TacticalAssessment.Evidence evidence : finding.evidence())
                require(
                        evidence.scenario() == scenario,
                        "Baseline evidence retains complete scenario");

    }


    // Support failure is not recipient failure \\

    private static void rejectedSupportWithSuccessfulMove(
            ProcessorMovementResolver resolver
    ) {

        UnitId mover = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);
        UnitId cutter = army(Nation.ENGLAND, Province.Bel);

        TacticalContext context = context(
                resolver,
                Map.of(
                        mover, Province.Par,
                        supporter, Province.Bur,
                        cutter, Province.Bel),
                Order.move(mover, Province.Pic),
                Order.supportMove(supporter, Province.Par, Province.Pic),
                Order.move(cutter, Province.Bur));

        TacticalAssessment assessment = new SupportToMoveAssessor().assess(
                only(new SupportToMoveDetective().investigate(context)),
                new TacticalScenario("cut-support-open-destination", context),
                resolver);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Truth.FALSE);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORTED_MOVE_SUCCEEDED,
                TacticalAssessment.Truth.TRUE);

    }


    // Support failure is not recipient dislodgement \\

    private static void rejectedSupportWithSurvival(
            ProcessorMovementResolver resolver
    ) {

        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);
        UnitId cutter = army(Nation.ENGLAND, Province.Bel);

        TacticalContext context = context(
                resolver,
                Map.of(
                        recipient, Province.Par,
                        supporter, Province.Bur,
                        cutter, Province.Bel),
                Order.hold(recipient),
                Order.supportHold(supporter, Province.Par),
                Order.move(cutter, Province.Bur));

        TacticalAssessment assessment = new SupportToHoldAssessor().assess(
                only(new SupportToHoldDetective().investigate(context)),
                new TacticalScenario("cut-support-surviving-recipient", context),
                resolver);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Truth.FALSE);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUBJECT_SURVIVED,
                TacticalAssessment.Truth.TRUE);

    }


    // Recipient dislodgement \\

    private static void dislodgedRecipient(
            ProcessorMovementResolver resolver
    ) {

        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);
        UnitId cutter = army(Nation.ENGLAND, Province.Bel);
        UnitId attacker = army(Nation.ENGLAND, Province.Pic);
        UnitId attackSupport = army(Nation.ENGLAND, Province.Bre);

        TacticalContext context = context(
                resolver,
                Map.of(
                        recipient, Province.Par,
                        supporter, Province.Bur,
                        cutter, Province.Bel,
                        attacker, Province.Pic,
                        attackSupport, Province.Bre),
                Order.hold(recipient),
                Order.supportHold(supporter, Province.Par),
                Order.move(cutter, Province.Bur),
                Order.move(attacker, Province.Par),
                Order.supportMove(attackSupport, Province.Pic, Province.Par));

        TacticalAssessment assessment = new SupportToHoldAssessor().assess(
                only(new SupportToHoldDetective().investigate(context)),
                new TacticalScenario("recipient-dislodged", context),
                resolver);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Truth.FALSE);

        requireTruth(assessment,
                TacticalAssessment.Claim.SUBJECT_SURVIVED,
                TacticalAssessment.Truth.FALSE);

    }


    // Unknown evidence and compatibility \\

    private static void unknownAndCompatibilityChecks(
            ProcessorMovementResolver resolver
    ) {

        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);

        Map<UnitId, Province> locations = Map.of(
                recipient, Province.Par,
                supporter, Province.Bur);

        Order support = Order.supportMove(
                supporter, Province.Par, Province.Pic);

        TacticalContext partial = context(resolver, locations, support);

        expect(IllegalArgumentException.class,
                () -> new TacticalScenario("partial", partial));

        TacticMatch incomplete = only(
                new SupportToMoveDetective().investigate(partial));

        require(!incomplete.completePattern(), "Recipient order is unknown");

        TacticalContext complete = context(
                resolver,
                locations,
                support,
                Order.move(recipient, Province.Pic));

        TacticalScenario scenario =
                new TacticalScenario("completed", complete);

        expect(IllegalArgumentException.class, () ->
                new SupportToMoveAssessor().assess(
                        incomplete, scenario, resolver));

        TacticMatch completeMatch = only(
                new SupportToMoveDetective().investigate(complete));

        expect(IllegalArgumentException.class, () ->
                resolver("other-engine").resolve(scenario));

        expect(IllegalArgumentException.class, () ->
                new SupportToMoveAssessor().assess(
                        completeMatch, scenario, resolver("other-engine")));

        TacticalContext changed = context(
                resolver,
                locations,
                support,
                Order.move(recipient, Province.Gas));

        expect(IllegalArgumentException.class, () ->
                new SupportToMoveAssessor().assess(
                        completeMatch,
                        new TacticalScenario("changed-known-move", changed),
                        resolver));

        expect(IllegalArgumentException.class, () ->
                new SupportToHoldAssessor().assess(
                        completeMatch, scenario, resolver));

    }


    // Fabricated match rejection \\

    private static void forgedMatchChecks(
            ProcessorMovementResolver resolver
    ) {

        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);

        TacticalContext context = context(
                resolver,
                Map.of(
                        recipient, Province.Par,
                        supporter, Province.Bur),
                Order.move(recipient, Province.Pic),
                Order.supportMove(supporter, Province.Par, Province.Pic));

        TacticMatch valid = only(
                new SupportToMoveDetective().investigate(context));

        TacticMatch forged = new TacticMatch(
                valid.kind(),
                valid.detectorVersion(),
                context,
                Province.Gas,
                valid.participants(),
                Set.of());

        expect(IllegalArgumentException.class, () ->
                new SupportToMoveAssessor().assess(
                        forged,
                        new TacticalScenario("forged-focus", context),
                        resolver));

        TacticMatch wrongVersion = new TacticMatch(
                valid.kind(),
                "unsupported-detective-version",
                context,
                valid.focus(),
                valid.participants(),
                Set.of());

        expect(IllegalArgumentException.class, () ->
                new SupportToMoveAssessor().assess(
                        wrongVersion,
                        new TacticalScenario("wrong-version", context),
                        resolver));

    }


    // Resolver correspondence rejection \\

    private static void mismatchedResultCheck(
            ProcessorMovementResolver resolver
    ) {

        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId supporter = army(Nation.FRANCE, Province.Bur);

        Map<UnitId, Province> locations = Map.of(
                recipient, Province.Par,
                supporter, Province.Bur);

        TacticalContext expected = context(
                resolver,
                locations,
                Order.move(recipient, Province.Pic),
                Order.supportMove(supporter, Province.Par, Province.Pic));

        TacticalContext different = context(
                resolver,
                locations,
                Order.hold(recipient),
                Order.hold(supporter));

        MovementResult wrongResult = resolver.resolve(
                new TacticalScenario("different-input", different));

        TacticAssessor.MovementResolver faulty =
                new TacticAssessor.MovementResolver() {

                    @Override
                    public String rulesetId() {
                        return resolver.rulesetId();
                    }

                    @Override
                    public MovementResult resolve(TacticalScenario ignored) {
                        return wrongResult;
                    }

                };

        expect(IllegalStateException.class, () ->
                new SupportToMoveAssessor().assess(
                        only(new SupportToMoveDetective().investigate(expected)),
                        new TacticalScenario("expected-input", expected),
                        faulty));

    }


    // Fixtures \\

    private static UnitId army(Nation nation, Province creationOrigin) {
        return new UnitId(nation, UnitType.ARMY, creationOrigin);
    }

    private static TacticalContext context(
            ProcessorMovementResolver resolver,
            Map<UnitId, Province> locations,
            Order... instructions
    ) {

        Map<UnitId, TacticalContext.KnownOrder> evidence =
                new LinkedHashMap<>();

        for (Order instruction : instructions) {

            TacticalContext.KnownOrder prior = evidence.put(
                    instruction.unit(),
                    new TacticalContext.KnownOrder(
                            instruction,
                            TacticalContext.Provenance.SUBMITTED));

            if (prior != null)
                throw new IllegalArgumentException(
                        "Duplicate fixture instruction");

        }

        return new TacticalContext(
                resolver.rulesetId(),
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, Map.of()),
                evidence);

    }

    private static TacticMatch only(List<TacticMatch> matches) {

        require(matches.size() == 1, "Expected exactly one fixture match");
        return matches.getFirst();

    }


    // Assertions \\

    private static void requireTruth(
            TacticalAssessment assessment,
            TacticalAssessment.Claim claim,
            TacticalAssessment.Truth expected
    ) {

        TacticalAssessment.Finding finding = assessment.findings().stream()
                .filter(value -> value.claim() == claim)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing claim " + claim));

        require(
                finding.truth() == expected,
                claim + ": expected " + expected + ", got " + finding.truth());

        require(
                !finding.evidence().isEmpty(),
                "Every claim requires evidence");

    }

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }

    private static void expect(
            Class<? extends Throwable> type,
            Runnable operation
    ) {

        checks++;

        try {

            operation.run();

        } catch (Throwable exception) {

            if (type.isInstance(exception))
                return;

            throw new AssertionError(
                    "Expected " + type.getSimpleName()
                            + ", got " + exception.getClass().getSimpleName(),
                    exception);

        }

        throw new AssertionError(
                "Expected " + type.getSimpleName());

    }


}