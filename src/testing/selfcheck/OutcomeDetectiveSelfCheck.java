package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.outcome.*;
import analysis.tactics.outcome.detective.SupporterDislodgementDetective;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.MovementInput;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Standalone checks for resolved context and supporter dislodgement.
 *
 * <p>Uses actual local adjudication. These finite fixtures do not prove
 * correctness for every board or adjudication policy.</p>
 */
public final class OutcomeDetectiveSelfCheck {


    // Constants and state \\

    private static final String RULESET =
            "outcome-selfcheck-v1;SZYKMAN_JUSTICE;trials=4;seed=0";

    private static int checks;


    // Construction \\

    private OutcomeDetectiveSelfCheck() {  }


    // Entry point \\

    public static void main(String[] args) {

        TacticAssessor.MovementResolver resolver = resolver();
        SupporterDislodgementDetective detective =
                new SupporterDislodgementDetective();

        // Creation origin intentionally differs from the actual location.
        UnitId supporter = army(Nation.FRANCE, Province.Mar);
        UnitId recipient = army(Nation.FRANCE, Province.Par);
        UnitId attacker = army(Nation.GERMANY, Province.Mun);
        UnitId helper = army(Nation.GERMANY, Province.Ruh);

        Map<UnitId, Province> board = Map.of(
                supporter, Province.Bur,
                recipient, Province.Par,
                attacker, Province.Mun,
                helper, Province.Ruh);

        TacticalScenario positive = scenario(
                "supported-attack",
                board,
                Order.supportHold(supporter, Province.Par),
                Order.hold(recipient),
                Order.move(attacker, Province.Bur),
                Order.supportMove(helper, Province.Mun, Province.Bur));

        ResolvedMovementContext resolved =
                ResolvedMovementContext.resolve(positive, resolver);

        List<OutcomeFinding> findings = detective.investigate(resolved);

        OutcomeFinding expected = new OutcomeFinding(
                TacticKind.SUPPORTER_DISLODGEMENT,
                detective.version(),
                resolved,
                Province.Bur,
                List.of(
                        new OutcomeFinding.Participant(
                                OutcomeFinding.Role.SUPPORTER, supporter),
                        new OutcomeFinding.Participant(
                                OutcomeFinding.Role.DISLODGED_UNIT, supporter),
                        new OutcomeFinding.Participant(
                                OutcomeFinding.Role.DISLODGER, attacker)));

        require(
                findings.equals(List.of(expected)),
                "Expected the exact supporter/dislodger finding");

        require(
                resolved.initialLocationOf(supporter) == Province.Bur,
                "Use current position, not creation origin");

        require(
                !resolved.result().finalLocations().containsKey(supporter),
                "Supporter must actually be dislodged");

        require(
                positive.context().board().locationOf(supporter) == Province.Bur,
                "Original scenario remains unchanged");

        require(
                findings.equals(detective.investigate(resolved)),
                "Repeated investigation is stable");

        expect(UnsupportedOperationException.class, findings::clear);

        TacticalScenario cutOnly = scenario(
                "unsupported-attack",
                board,
                Order.supportHold(supporter, Province.Par),
                Order.hold(recipient),
                Order.move(attacker, Province.Bur),
                Order.hold(helper));

        ResolvedMovementContext cutResult =
                ResolvedMovementContext.resolve(cutOnly, resolver);

        require(
                !cutResult.result().outcomes().get(supporter).succeeded(),
                "Fixture support should fail");

        require(
                detective.investigate(cutResult).isEmpty(),
                "Failed support without dislodgement must not qualify");

        TacticalScenario holdingVictim = scenario(
                "holding-victim",
                board,
                Order.hold(supporter),
                Order.hold(recipient),
                Order.move(attacker, Province.Bur),
                Order.supportMove(helper, Province.Mun, Province.Bur));

        ResolvedMovementContext holdingResult =
                ResolvedMovementContext.resolve(holdingVictim, resolver);

        require(
                holdingResult.result().dislodgements().containsKey(supporter),
                "Holding victim fixture should be dislodged");

        require(
                detective.investigate(holdingResult).isEmpty(),
                "Dislodged non-supporter must not qualify");

        TacticalScenario invalidSupport = scenario(
                "invalid-support",
                board,
                Order.supportHold(supporter, Province.Syr),
                Order.hold(recipient),
                Order.move(attacker, Province.Bur),
                Order.supportMove(helper, Province.Mun, Province.Bur));

        require(
                detective.investigate(
                        ResolvedMovementContext.resolve(
                                invalidSupport, resolver)).size() == 1,
                "Invalid original SUPPORT still identifies a dislodged supporter");

        expect(IllegalArgumentException.class, () ->
                scenario(
                        "incomplete",
                        board,
                        Order.hold(recipient)));

        TacticAssessor.MovementResolver wrongRuleset =
                new TacticAssessor.MovementResolver() {

                    @Override
                    public String rulesetId() {
                        return "different-policy";
                    }

                    @Override
                    public MovementResult resolve(TacticalScenario ignored) {
                        throw new AssertionError("Must reject before resolving");
                    }

                };

        expect(IllegalArgumentException.class, () ->
                ResolvedMovementContext.resolve(positive, wrongRuleset));

        TacticAssessor.MovementResolver wrongResult =
                new TacticAssessor.MovementResolver() {

                    @Override
                    public String rulesetId() {
                        return RULESET;
                    }

                    @Override
                    public MovementResult resolve(TacticalScenario ignored) {
                        return holdingResult.result();
                    }

                };

        expect(IllegalStateException.class, () ->
                ResolvedMovementContext.resolve(positive, wrongResult));

        expect(NullPointerException.class, () -> detective.investigate(null));

        System.out.println(
                "OutcomeDetectiveSelfCheck passed: " + checks + " checks");

    }


    // Fixtures \\

    private static UnitId army(Nation nation, Province creationOrigin) {
        return new UnitId(nation, UnitType.ARMY, creationOrigin);
    }

    private static TacticalScenario scenario(
            String id,
            Map<UnitId, Province> board,
            Order... orders
    ) {

        Map<UnitId, TacticalContext.KnownOrder> known = new LinkedHashMap<>();

        for (Order order : orders) {

            if (known.putIfAbsent(
                    order.unit(),
                    new TacticalContext.KnownOrder(
                            order, TacticalContext.Provenance.SUBMITTED)) != null)
                throw new IllegalArgumentException("Duplicate fixture order");

        }

        TacticalContext context = new TacticalContext(
                RULESET,
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(board, Map.of()),
                known);

        return new TacticalScenario(id, context);

    }

    private static TacticAssessor.MovementResolver resolver() {

        return new TacticAssessor.MovementResolver() {

            @Override
            public String rulesetId() {
                return RULESET;
            }

            @Override
            public MovementResult resolve(TacticalScenario scenario) {

                Objects.requireNonNull(scenario, "scenario");

                if (!RULESET.equals(scenario.context().rulesetId()))
                    throw new IllegalArgumentException("Ruleset mismatch");

                Map<UnitId, Province> positions = new LinkedHashMap<>();
                List<Order> orders = new ArrayList<>();

                // TacticalContext orders are sorted by actual location.
                for (Map.Entry<UnitId, TacticalContext.KnownOrder> entry
                        : scenario.context().orders().entrySet()) {

                    positions.put(
                            entry.getKey(),
                            scenario.context().board().locationOf(entry.getKey()));

                    orders.add(entry.getValue().order());

                }

                return new MovementProcessor(
                        MovementProcessor.Policy.SZYKMAN_JUSTICE,
                        4,
                        0L).process(new MovementInput(positions, orders));

            }

        };

    }


    // Assertions \\

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }

    private static void expect(
            Class<? extends Throwable> type,
            Runnable action
    ) {

        checks++;

        try {

            action.run();

        } catch (Throwable failure) {

            if (type.isInstance(failure))
                return;

            throw new AssertionError("Unexpected failure type", failure);

        }

        throw new AssertionError("Expected " + type.getSimpleName());

    }


}