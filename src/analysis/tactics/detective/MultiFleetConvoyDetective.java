package analysis.tactics.detective;

import analysis.tactics.*;

import java.util.*;


/** At least two known fleet CONVOYs name the same known army MOVE; no route claim. */
public final class MultiFleetConvoyDetective extends Detective {

    // Constants \\

    private static final String VERSION = "multi-fleet-correspondence-v1";


    // Construction \\

    public MultiFleetConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MULTI_FLEET_CONVOY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (ConvoyRelationships.ArmyMove move : ConvoyRelationships.moves(context))
            if (move.fleets().size() >= 2)
                findings.add(new TacticMatch(kind(), version(), context, move.destination(),
                        ConvoyRelationships.participants(move.army(), move.fleets()), Set.of()));

        return findings;

    }

}
