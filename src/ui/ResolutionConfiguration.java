package ui;

import analysis.tactics.assessment.ProcessorMovementResolver;
import analysis.tactics.outcome.ResolvedMovementContext;
import phase.movement.MovementProcessor;

import java.util.Objects;


/**
 * Browser metadata for an identified local resolution.
 *
 * <p>Optional processor fields are either all present or all absent.
 * An identity-only configuration supports callers using custom resolvers.</p>
 */
public record ResolutionConfiguration(
        String rulesetId,
        String scenarioId,
        String engineRevision,
        MovementProcessor.Policy policy,
        Integer trialCount,
        Long shuffleSeed
) {


    // Construction \\

    public ResolutionConfiguration {

        Objects.requireNonNull(rulesetId, "rulesetId");
        Objects.requireNonNull(scenarioId, "scenarioId");

        if (rulesetId.isBlank() || scenarioId.isBlank())
            throw new IllegalArgumentException(
                    "Resolution identities must not be blank");

        boolean anyProcessorField = engineRevision != null
                || policy != null || trialCount != null || shuffleSeed != null;

        if (anyProcessorField) {

            Objects.requireNonNull(engineRevision, "engineRevision");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(trialCount, "trialCount");
            Objects.requireNonNull(shuffleSeed, "shuffleSeed");

            if (engineRevision.isBlank() || trialCount < 1)
                throw new IllegalArgumentException(
                        "Invalid processor configuration");

        }

    }


    // Factories \\

    public static ResolutionConfiguration identity(
            ResolvedMovementContext resolved
    ) {

        Objects.requireNonNull(resolved, "resolved");

        return new ResolutionConfiguration(
                resolved.rulesetId(),
                resolved.scenario().id(),
                null, null, null, null);

    }

    public static ResolutionConfiguration from(
            ProcessorMovementResolver resolver,
            ResolvedMovementContext resolved
    ) {

        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(resolved, "resolved");

        if (!resolver.rulesetId().equals(resolved.rulesetId()))
            throw new IllegalArgumentException(
                    "Configuration differs from the resolved context");

        return new ResolutionConfiguration(
                resolved.rulesetId(),
                resolved.scenario().id(),
                resolver.engineRevision(),
                resolver.policy(),
                resolver.trialCount(),
                resolver.shuffleSeed());

    }

    public boolean hasProcessorDetails() {
        return policy != null;
    }


}