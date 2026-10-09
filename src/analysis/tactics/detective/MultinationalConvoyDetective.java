package analysis.tactics.detective;

import analysis.tactics.*;

import java.util.*;


/** Matching fleet CONVOYs from at least two fleet powers; army power is not counted. */
public final class MultinationalConvoyDetective extends Detective {

    // Constants \\

    private static final String VERSION = "multinational-correspondence-v1";


    // Construction \\

    public MultinationalConvoyDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.MULTINATIONAL_CONVOY;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        List<TacticMatch> findings = new ArrayList<>();

        for (ConvoyRelationships.ArmyMove move : ConvoyRelationships.moves(context))
            if (move.fleets().stream().map(unit -> unit.owner()).distinct().count() >= 2)
                findings.add(new TacticMatch(kind(), version(), context, move.destination(),
                        ConvoyRelationships.participants(move.army(), move.fleets()), Set.of()));

        return findings;

    }

}
