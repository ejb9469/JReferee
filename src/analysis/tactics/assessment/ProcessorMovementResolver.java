package analysis.tactics.assessment;

import analysis.tactics.TacticAssessor;
import analysis.tactics.TacticalContext;
import analysis.tactics.TacticalScenario;
import domain.Province;
import phase.MovementInput;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * Resolves complete tactical scenarios through MovementProcessor.
 *
 * <p>Each invocation creates fresh processor and adjudication work objects.
 * No live Game is advanced and no caller-owned context is modified.</p>
 *
 * <p>The identity includes the chosen policy, trial count, seed, adapter
 * protocol, and a caller-supplied engine revision. The revision must identify
 * the actual adjudicator/map implementation used by the running application.</p>
 */
public final class ProcessorMovementResolver
        implements TacticAssessor.MovementResolver {


    // Constants \\

    private static final String PROTOCOL = "processor-movement-v1";


    // Core state \\

    private final MovementProcessor.Policy policy;
    private final int trialCount;
    private final long shuffleSeed;
    private final String engineRevision;
    private final String rulesetId;


    // Construction \\

    public ProcessorMovementResolver(
            MovementProcessor.Policy policy,
            int trialCount,
            long shuffleSeed,
            String engineRevision
    ) {

        this.policy = Objects.requireNonNull(policy, "policy");

        if (trialCount < 1)
            throw new IllegalArgumentException(
                    "trialCount must be at least 1");

        Objects.requireNonNull(engineRevision, "engineRevision");

        if (engineRevision.isBlank())
            throw new IllegalArgumentException(
                    "engineRevision must not be blank");

        this.trialCount = trialCount;
        this.shuffleSeed = shuffleSeed;
        this.engineRevision = engineRevision;

        String encodedRevision = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        engineRevision.getBytes(StandardCharsets.UTF_8));

        this.rulesetId = PROTOCOL
                + ";engine=" + encodedRevision
                + ";policy=" + policy.name()
                + ";trials=" + trialCount
                + ";seed=" + shuffleSeed;

    }


    // Configuration \\

    @Override
    public String rulesetId() {
        return rulesetId;
    }

    public MovementProcessor.Policy policy() {
        return policy;
    }

    public int trialCount() {
        return trialCount;
    }

    public long shuffleSeed() {
        return shuffleSeed;
    }

    public String engineRevision() {
        return engineRevision;
    }


    // Resolution \\

    @Override
    public MovementResult resolve(TacticalScenario scenario) {

        Objects.requireNonNull(scenario, "scenario");

        TacticalContext context = scenario.context();

        if (!rulesetId.equals(context.rulesetId()))
            throw new IllegalArgumentException(
                    "Scenario ruleset does not match this resolver");

        /*
         * TacticalScenario already checks completeness. Keep the boundary
         * explicit here because MovementProcessor otherwise creates HOLDs
         * for instructions omitted from its input.
         */
        if (!context.complete())
            throw new IllegalArgumentException(
                    "Unknown orders cannot be defaulted during assessment");

        List<UnitId> units = new ArrayList<>(
                context.board().locations().keySet());

        /*
         * Normalize iteration order independently of map insertion order
         * and persistent UUIDs.
         */
        units.sort(
                Comparator.comparing(
                                (UnitId unit) ->
                                        context.board().locationOf(unit).name())
                        .thenComparing(unit -> unit.owner().name())
                        .thenComparing(unit -> unit.unitType().name()));

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        List<Order> instructions = new ArrayList<>();

        for (UnitId unit : units) {

            locations.put(unit, context.board().locationOf(unit));

            Order submitted = context.orders().get(unit).order();

            instructions.add(new Order(
                    unit,
                    submitted.orderType(),
                    submitted.target(),
                    submitted.auxiliaryTarget()));

        }

        MovementProcessor processor = new MovementProcessor(
                policy,
                trialCount,
                shuffleSeed);

        MovementResult result = processor.process(
                new MovementInput(locations, instructions));

        MovementResolutionChecks.requireCorrespondence(scenario, result);

        return result;

    }


}