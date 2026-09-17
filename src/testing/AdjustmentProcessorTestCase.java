package testing;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.UnitId;
import phase.adjustments.AdjustmentInput;
import phase.adjustments.AdjustmentProcessor;
import phase.adjustments.AdjustmentResult;
import phase.adjustments.Balance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Concrete test case for DATC section-I building cases and section-J civil
 * disorder / disband cases.
 */
public final class AdjustmentProcessorTestCase
        extends ProcessorTestCase<AdjustmentInput, AdjustmentResult> {


    private final List<AdjustmentResult.Outcome.Status> expectedOrderOutcomes;
    private final Map<Nation, Balance> expectedBalances;
    private final Map<UnitId, Province> expectedExistingFinalLocations;
    private final Set<UnitId> expectedDisbandedUnits;
    private final Set<ExpectedBuild> expectedBuilds;
    private final int expectedFinalUnitCount;


    public AdjustmentProcessorTestCase(
            String name,
            AdjustmentInput input,
            List<AdjustmentResult.Outcome.Status> expectedOrderOutcomes,
            Map<Nation, Balance> expectedBalances,
            Map<UnitId, Province> expectedExistingFinalLocations,
            Set<UnitId> expectedDisbandedUnits,
            Set<ExpectedBuild> expectedBuilds,
            int expectedFinalUnitCount
    ) {

        super(name, new AdjustmentProcessor(), input);

        this.expectedOrderOutcomes = List.copyOf(
                Objects.requireNonNull(
                        expectedOrderOutcomes,
                        "expectedOrderOutcomes"));

        this.expectedBalances = Map.copyOf(
                Objects.requireNonNull(
                        expectedBalances,
                        "expectedBalances"));

        this.expectedExistingFinalLocations = Map.copyOf(
                Objects.requireNonNull(
                        expectedExistingFinalLocations,
                        "expectedExistingFinalLocations"));

        this.expectedDisbandedUnits = Set.copyOf(
                Objects.requireNonNull(
                        expectedDisbandedUnits,
                        "expectedDisbandedUnits"));

        this.expectedBuilds = Set.copyOf(
                Objects.requireNonNull(
                        expectedBuilds,
                        "expectedBuilds"));

        if (expectedFinalUnitCount < 0)
            throw new IllegalArgumentException(
                    "expectedFinalUnitCount cannot be negative");
        this.expectedFinalUnitCount = expectedFinalUnitCount;

    }


    @Override
    protected TestEvaluation verify(AdjustmentResult result) {

        TestEvaluation.Builder evaluation =
                new TestEvaluation.Builder();

        evaluation.expect(
                "Adjustment order outcomes",
                expectedOrderOutcomes,
                statusesOf(result));

        for (Map.Entry<Nation, Balance> entry :
                expectedBalances.entrySet()) {
            evaluation.expect(
                    "Balance for " + entry.getKey(),
                    entry.getValue(),
                    result.balances().get(entry.getKey()));
        }

        for (Map.Entry<UnitId, Province> entry :
                expectedExistingFinalLocations.entrySet()) {
            evaluation.expect(
                    "Final location for " + entry.getKey(),
                    entry.getValue(),
                    result.finalLocations().get(entry.getKey()));
        }

        evaluation.expect(
                "Disbanded units",
                expectedDisbandedUnits,
                result.disbandedUnits());

        evaluation.expect(
                "Built units",
                expectedBuilds,
                buildsOf(result));

        evaluation.expect(
                "Final unit count",
                expectedFinalUnitCount,
                result.finalLocations().size());

        return evaluation.build();

    }

    private static List<AdjustmentResult.Outcome.Status> statusesOf(AdjustmentResult result) {
        return result.outcomes().stream()
                .map(AdjustmentResult.Outcome::status)
                .toList();
    }

    private static Set<ExpectedBuild> buildsOf(AdjustmentResult result) {

        Set<ExpectedBuild> builds = new LinkedHashSet<>();

        for (UnitId unit : result.builtUnits()) {
            builds.add(
                    new ExpectedBuild(
                            unit.owner(),
                            unit.unitType(),
                            result.finalLocations().get(unit)));
        }

        return Set.copyOf(builds);
    }


    /**
     * UUID-independent description of a unit successfully built in winter.
     */
    public record ExpectedBuild(
            Nation nation,
            UnitType unitType,
            Province location
    ) {
        public ExpectedBuild {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(unitType, "unitType");
            Objects.requireNonNull(location, "location");
        }
    }

}