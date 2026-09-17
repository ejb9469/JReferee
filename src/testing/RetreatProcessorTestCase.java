package testing;

import domain.Province;
import phase.UnitId;
import phase.retreats.RetreatInput;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Concrete test case for DATC section-H retreat cases.
 */
public final class RetreatProcessorTestCase
        extends ProcessorTestCase<RetreatInput, RetreatResult> {


    private final Map<UnitId, RetreatResult.Outcome.Status> expectedOutcomes;
    private final Map<UnitId, Province> expectedFinalLocations;
    private final Set<UnitId> expectedDestroyedUnits;


    public RetreatProcessorTestCase(
            String name,
            RetreatInput input,
            Map<UnitId, RetreatResult.Outcome.Status> expectedOutcomes,
            Map<UnitId, Province> expectedFinalLocations,
            Set<UnitId> expectedDestroyedUnits
    ) {

        super(name, new RetreatProcessor(), input);

        this.expectedOutcomes = Map.copyOf(
                Objects.requireNonNull(
                        expectedOutcomes,
                        "expectedOutcomes"));

        this.expectedFinalLocations = Map.copyOf(
                Objects.requireNonNull(
                        expectedFinalLocations,
                        "expectedFinalLocations"));

        this.expectedDestroyedUnits = Set.copyOf(
                Objects.requireNonNull(
                        expectedDestroyedUnits,
                        "expectedDestroyedUnits"));

    }


    @Override
    protected TestEvaluation verify(RetreatResult result) {

        TestEvaluation.Builder evaluation =
                new TestEvaluation.Builder();

        evaluation.expect(
                "Retreat outcomes",
                expectedOutcomes,
                statusesOf(result));

        evaluation.expect(
                "Post-retreat board",
                expectedFinalLocations,
                result.finalLocations());

        evaluation.expect(
                "Destroyed retreat units",
                expectedDestroyedUnits,
                result.destroyedUnits());

        return evaluation.build();

    }

    private static Map<UnitId, RetreatResult.Outcome.Status> statusesOf(RetreatResult result) {

        java.util.LinkedHashMap<UnitId, RetreatResult.Outcome.Status>
                statuses = new java.util.LinkedHashMap<>();

        for (Map.Entry<UnitId, RetreatResult.Outcome> entry :
                result.outcomes().entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().status());
        }

        return Map.copyOf(statuses);

    }

}