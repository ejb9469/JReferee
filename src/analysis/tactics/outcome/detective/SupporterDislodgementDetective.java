package analysis.tactics.outcome.detective;

import analysis.tactics.TacticKind;
import analysis.tactics.outcome.OutcomeDetective;
import analysis.tactics.outcome.OutcomeFinding;
import analysis.tactics.outcome.ResolvedMovementContext;
import domain.OrderType;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Recognizes units issuing SUPPORT that were dislodged during movement.
 *
 * <p>The original instruction identifies the supporter. Support validity,
 * correspondence, and effectiveness are not prerequisites.</p>
 *
 * <p>A rejected support without dislodgement is not a finding.
 * Dislodgement does not establish destruction after retreats or explain
 * the complete cause of a support's failure.</p>
 */
public final class SupporterDislodgementDetective extends OutcomeDetective {


    // Construction \\

    public SupporterDislodgementDetective() {
        super("supporter-dislodgement-v1");
    }


    // Identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.SUPPORTER_DISLODGEMENT;
    }


    // Examination \\

    @Override
    protected List<OutcomeFinding> examine(
            ResolvedMovementContext context
    ) {

        List<OutcomeFinding> findings = new ArrayList<>();

        for (MovementResult.Dislodgement dislodgement
                : context.result().dislodgements().values()) {

            UnitId supporter = dislodgement.unit();

            if (context.scenario().context().orders()
                    .get(supporter).order().orderType() != OrderType.SUPPORT)
                continue;

            findings.add(new OutcomeFinding(
                    kind(),
                    version(),
                    context,
                    dislodgement.displacedFrom(),
                    List.of(
                            new OutcomeFinding.Participant(
                                    OutcomeFinding.Role.SUPPORTER,
                                    supporter),
                            new OutcomeFinding.Participant(
                                    OutcomeFinding.Role.DISLODGED_UNIT,
                                    supporter),
                            new OutcomeFinding.Participant(
                                    OutcomeFinding.Role.DISLODGER,
                                    dislodgement.attacker()))));

        }

        return findings;

    }


}