package analysis.tactics;

import java.util.*;


/**
 * Immutable semantics and evidence contract for one tactical concept.
 */
public record TacticDefinition(
        TacticKind kind,
        TacticFamily family,
        TacticInterpretation interpretation,
        Set<EvidenceCapability> requiredEvidence,
        String semanticVersion,
        String semantics
) {


    // Construction and validation \\

    public TacticDefinition {

        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(interpretation, "interpretation");
        Objects.requireNonNull(requiredEvidence, "requiredEvidence");
        Objects.requireNonNull(semanticVersion, "semanticVersion");
        Objects.requireNonNull(semantics, "semantics");

        if (semanticVersion.isBlank() || semantics.isBlank())
            throw new IllegalArgumentException(
                    "Definition version and semantics must not be blank");

        EnumSet<EvidenceCapability> capabilities =
                EnumSet.noneOf(EvidenceCapability.class);

        for (EvidenceCapability capability : requiredEvidence)
            capabilities.add(Objects.requireNonNull(
                    capability, "required evidence capability"));

        requiredEvidence = Collections.unmodifiableSet(capabilities);

    }


}
