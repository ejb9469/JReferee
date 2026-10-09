package analysis.tactics.detective;

import analysis.tactics.*;

import java.util.*;


/**
 * Known army MOVE and fleet CONVOY name the same actual army territory and
 * canonical destination. One occurrence per army includes all matching fleets.
 * Matching submissions need not be geographically legal or provide a route.
 */
public final class ConvoyedMoveDetective extends Detective {

    // Constants \\

    private static final String VERSION = "convoy-correspondence-v1";


    // Construction \\

    public ConvoyedMoveDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.CONVOYED_MOVE;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (ConvoyRelationships.ArmyMove move : ConvoyRelationships.moves(context))
            findings.add(new TacticMatch(kind(), version(), context, move.destination(),
                    ConvoyRelationships.participants(move.army(), move.fleets()), Set.of()));

        return findings;

    }

}
