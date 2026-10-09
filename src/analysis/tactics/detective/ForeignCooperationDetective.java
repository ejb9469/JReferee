package analysis.tactics.detective;

import analysis.tactics.*;
import domain.Geography;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes explicit cross-power assistance and vacancy relationships.
 *
 * <p>Findings identify candidate cooperation dependencies, not proven
 * necessity, agreement, intent, or successful execution.</p>
 *
 * <p>Foreign assistance may be optional or hostile. A move into a foreign
 * unit's vacated territory may also have succeeded by dislodging it.
 * Such distinctions require scenario assessment.</p>
 */
public final class ForeignCooperationDetective extends Detective {


    // Construction \\

    public ForeignCooperationDetective() {
        super("foreign-cooperation-reference-v1");
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.FOREIGN_COOPERATION_DEPENDENCY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        collectSupport(context, new SupportToMoveDetective(), findings);
        collectSupport(context, new SupportToHoldDetective(), findings);
        collectConvoys(context, findings);
        collectVacancies(context, findings);

        return findings;

    }


    // Foreign support \\

    private void collectSupport(
            TacticalContext context,
            Detective source,
            List<TacticMatch> findings
    ) {

        for (TacticMatch relationship : source.investigate(context)) {

            UnitId supporter =
                    relationship.units(TacticMatch.Role.SUPPORTER).getFirst();

            UnitId recipient =
                    relationship.units(TacticMatch.Role.SUPPORTED_UNIT).getFirst();

            if (supporter.owner() == recipient.owner())
                continue;

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    relationship.focus(),
                    relationship.participants(),
                    relationship.missingOrders()));

        }

    }


    // Foreign convoy submissions \\

    private void collectConvoys(
            TacticalContext context,
            List<TacticMatch> findings
    ) {

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order convoy = evidence.order();

            if (convoy.orderType() != OrderType.CONVOY
                    || convoy.unitType() != UnitType.FLEET)
                continue;

            Province fleetLocation =
                    context.board().locationOf(convoy.unit());

            if (fleetLocation.geography != Geography.WATER)
                continue;

            Optional<UnitId> referenced = context.unitAt(convoy.target());

            if (referenced.isEmpty())
                continue;

            UnitId army = referenced.get();

            if (army.unitType() != UnitType.ARMY
                    || army.owner() == convoy.owner())
                continue;

            Province origin = Province.canonical(
                    context.board().locationOf(army));

            Province destination =
                    Province.canonical(convoy.auxiliaryTarget());

            if (origin == destination
                    || !origin.hasCoast()
                    || !destination.hasCoast())
                continue;

            TacticalContext.KnownOrder armyEvidence =
                    context.orders().get(army);

            Set<UnitId> missing = Set.of();

            if (armyEvidence == null) {

                missing = Set.of(army);

            } else {

                Order move = armyEvidence.order();

                if (move.orderType() != OrderType.MOVE
                        || Province.canonical(move.target()) != destination)
                    continue;

            }

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    destination,
                    List.of(
                            new TacticMatch.Participant(
                                    TacticMatch.Role.CONVOYING_UNIT,
                                    convoy.unit()),
                            new TacticMatch.Participant(
                                    TacticMatch.Role.CONVOYED_ARMY,
                                    army)),
                    missing));

        }

    }


    // Foreign vacancy relationships \\

    private void collectVacancies(
            TacticalContext context,
            List<TacticMatch> findings
    ) {

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Optional<UnitId> occupant = context.unitAt(move.target());

            if (occupant.isEmpty()
                    || occupant.get().owner() == move.owner())
                continue;

            UnitId resident = occupant.get();

            TacticalContext.KnownOrder residentEvidence =
                    context.orders().get(resident);

            if (residentEvidence == null)
                continue;

            Order departure = residentEvidence.order();

            if (departure.orderType() != OrderType.MOVE)
                continue;

            Province location = Province.canonical(
                    context.board().locationOf(resident));

            if (Province.canonical(departure.target()) == location)
                continue;

            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    location,
                    List.of(
                            new TacticMatch.Participant(
                                    TacticMatch.Role.ISSUER, move.unit()),
                            new TacticMatch.Participant(
                                    TacticMatch.Role.REFERENCED_UNIT, resident)),
                    Set.of()));

        }

    }


}