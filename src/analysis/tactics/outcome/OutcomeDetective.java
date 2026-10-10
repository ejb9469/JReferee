package analysis.tactics.outcome;

import analysis.tactics.TacticInvestigator;
import analysis.tactics.TacticKind;

import java.util.*;


/**
 * Base for investigators of already-resolved movement facts.
 *
 * <p>Multiple outcome detectives inspect the same resolved context.
 * Investigation does not invoke adjudication.</p>
 */
public abstract class OutcomeDetective
        implements TacticInvestigator<ResolvedMovementContext, OutcomeFinding> {


    // Core state \\

    private final String version;


    // Construction \\

    protected OutcomeDetective(String version) {

        Objects.requireNonNull(version, "version");

        if (version.isBlank())
            throw new IllegalArgumentException(
                    "Investigator version must not be blank");

        this.version = version;

    }


    // Identity \\

    @Override
    public abstract TacticKind kind();

    @Override
    public final String version() {
        return version;
    }


    // Investigation \\

    @Override
    public final List<OutcomeFinding> investigate(
            ResolvedMovementContext context
    ) {

        Objects.requireNonNull(context, "context");

        List<OutcomeFinding> findings = new ArrayList<>(
                Objects.requireNonNull(examine(context), "findings"));

        Set<OutcomeFinding> observed = new HashSet<>();

        for (OutcomeFinding finding : findings) {

            if (finding == null
                    || finding.kind() != kind()
                    || !version.equals(finding.investigatorVersion())
                    || finding.context() != context)
                throw new IllegalStateException(
                        "Finding violates outcome-investigator contract");

            if (!observed.add(finding))
                throw new IllegalStateException(
                        "Duplicate outcome finding");

        }

        findings.sort(OutcomeReport::compareFindings);

        return List.copyOf(findings);

    }

    protected abstract List<OutcomeFinding> examine(
            ResolvedMovementContext context);


}