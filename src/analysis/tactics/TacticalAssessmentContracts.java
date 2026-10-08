package analysis.tactics;

import java.util.*;


/**
 * Explicit registry of kinds for which outcome claims are currently defined.
 */
public final class TacticalAssessmentContracts {


    // Registered claim contracts \\

    private static final Map<TacticKind, Set<TacticalAssessment.Claim>> CONTRACTS =
            createContracts();


    // Construction \\

    private TacticalAssessmentContracts() {  }


    // Queries \\

    public static Set<TacticKind> supportedKinds() {
        return CONTRACTS.keySet();
    }

    public static Set<TacticalAssessment.Claim> requiredClaims(TacticKind kind) {

        Objects.requireNonNull(kind, "kind");

        Set<TacticalAssessment.Claim> claims = CONTRACTS.get(kind);

        if (claims == null)
            throw new IllegalArgumentException(
                    "No assessment claim contract registered for " + kind);

        return claims;

    }


    // Contract construction \\

    private static Map<TacticKind, Set<TacticalAssessment.Claim>>
    createContracts() {

        Map<TacticKind, Set<TacticalAssessment.Claim>> contracts =
                new EnumMap<>(TacticKind.class);

        register(contracts, TacticKind.SUPPORT_TO_MOVE,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Claim.SUPPORTED_MOVE_SUCCEEDED);
        register(contracts, TacticKind.SUPPORT_TO_HOLD,
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                TacticalAssessment.Claim.SUBJECT_SURVIVED);
        register(contracts, TacticKind.SELF_BOUNCE,
                TacticalAssessment.Claim.SELF_BOUNCE_REALIZED);
        register(contracts, TacticKind.BELEAGUERED_GARRISON,
                TacticalAssessment.Claim.GARRISON_PRESERVED,
                TacticalAssessment.Claim.COMPETITION_SAVED_GARRISON);

        return Collections.unmodifiableMap(contracts);

    }

    private static void register(
            Map<TacticKind, Set<TacticalAssessment.Claim>> contracts,
            TacticKind kind,
            TacticalAssessment.Claim... claims
    ) {

        EnumSet<TacticalAssessment.Claim> required =
                EnumSet.noneOf(TacticalAssessment.Claim.class);

        for (TacticalAssessment.Claim claim : claims)
            if (!required.add(Objects.requireNonNull(claim, "claim")))
                throw new ExceptionInInitializerError(
                        "Duplicate assessment claim for " + kind);

        if (required.isEmpty()
                || contracts.putIfAbsent(
                        Objects.requireNonNull(kind, "kind"),
                        Collections.unmodifiableSet(required)) != null)
            throw new ExceptionInInitializerError(
                    "Invalid assessment claim contract for " + kind);

    }


}
