package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.SupportToMoveDetective;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Standalone checks for tactic definitions, assessment contracts, and coverage.
 */
public final class TacticArchitectureSelfCheck {


    // Constants \\

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);


    // Check state \\

    private static int checks;


    // Construction \\

    private TacticArchitectureSelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        checks = 0;

        definitionCatalogue();
        coverage();
        assessmentContracts();
        validationAndImmutability();

        System.out.printf(
                "Tactic architecture self-check passed: %d checks.%n",
                checks);

    }


    // Definition catalogue \\

    private static void definitionCatalogue() {

        Map<TacticKind, TacticDefinition> definitions =
                TacticDefinitionRegistry.definitions();

        require(definitions.size() == TacticKind.values().length,
                "Definition registry does not cover every kind");
        require(definitions.keySet().equals(
                        EnumSet.allOf(TacticKind.class)),
                "Definition registry has missing or extra kinds");

        Set<String> stableIdentifiers = new HashSet<>();

        for (TacticKind kind : TacticKind.values()) {

            TacticDefinition definition =
                    TacticDefinitionRegistry.require(kind);

            require(stableIdentifiers.add(kind.name()),
                    "Duplicate stable tactic identifier: " + kind.name());
            require(definition.kind() == kind,
                    "Definition has a different kind identifier");
            require(!definition.semanticVersion().isBlank()
                            && !definition.semantics().isBlank(),
                    "Definition omitted version or semantics");
            require(!definition.requiredEvidence().isEmpty(),
                    "Definition omitted necessary evidence capabilities");

        }

        require(TacticDefinitionRegistry.require(TacticKind.SUPPORT_TO_MOVE)
                        .requiredEvidence()
                        .equals(EnumSet.of(
                                EvidenceCapability.MOVEMENT_POSITION,
                                EvidenceCapability.KNOWN_MOVEMENT_ORDERS)),
                "Structural support definition requires the wrong evidence");
        require(TacticDefinitionRegistry.require(
                        TacticKind.RETREAT_INTO_SUPPLY_CENTER)
                        .requiredEvidence()
                        .containsAll(EnumSet.of(
                                EvidenceCapability.RETREAT_ORDERS,
                                EvidenceCapability.SUPPLY_CENTER_STATE)),
                "Supply-center retreat definition omits required evidence");
        require(TacticDefinitionRegistry.require(
                        TacticKind.CONVOY_KIDNAPPING)
                        .requiredEvidence()
                        .contains(EvidenceCapability.RULESET_POLICY),
                "Convoy-kidnapping definition omits ruleset policy evidence");
        require(TacticDefinitionRegistry.require(
                        TacticKind.PROMISED_SUPPORT_WITHHELD)
                        .interpretation() == TacticInterpretation.AGREEMENT
                        && TacticDefinitionRegistry.require(
                        TacticKind.PROMISED_SUPPORT_WITHHELD)
                        .requiredEvidence()
                        .containsAll(EnumSet.of(
                                EvidenceCapability.AGREEMENT_RECORD,
                                EvidenceCapability.INTENT_EVIDENCE)),
                "Agreement interpretation infers promises without agreement evidence");

    }


    // Investigation coverage \\

    private static void coverage() {

        TacticalContext empty = context(
                Map.of(),
                Map.of());
        DetectiveAgency standard = new DetectiveAgency();
        InvestigationReport noFindings = standard.investigateReport(empty);

        InvestigationCoverage.KindCoverage support =
                coverage(noFindings, TacticKind.SUPPORT_TO_MOVE);

        require(support.implemented() && support.applicable(),
                "Registered structural detector was not applicable");
        require(support.status()
                        == InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS,
                "Empty result was not distinguished from unimplemented");

        InvestigationCoverage.KindCoverage future =
                coverage(noFindings, TacticKind.FALL_CENTER_CAPTURE);

        require(!future.implemented() && !future.applicable()
                        && future.status()
                        == InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED,
                "Unimplemented kind was reported as a negative match");

        int[] calls = { 0 };
        TacticDetector centerInvestigator = new TacticDetector() {

            @Override
            public TacticKind kind() {
                return TacticKind.FALL_CENTER_CAPTURE;
            }

            @Override
            public String version() {
                return "test-v1";
            }

            @Override
            public List<TacticMatch> detect(TacticalContext ignored) {
                calls[0]++;
                throw new AssertionError(
                        "Investigator ran without required evidence");
            }

        };

        InvestigationCoverage.KindCoverage skipped =
                new DetectiveAgency(List.of(centerInvestigator))
                        .investigateReport(empty)
                        .coverage()
                        .byKind()
                        .stream()
                        .filter(entry -> entry.kind()
                                == TacticKind.FALL_CENTER_CAPTURE)
                        .findFirst()
                        .orElseThrow();

        require(skipped.implemented() && !skipped.applicable(),
                "Registered investigator's applicability was not reported");
        require(skipped.status()
                        == InvestigationCoverage.Status.SKIPPED_MISSING_EVIDENCE
                        && skipped.missingEvidence().contains(
                        EvidenceCapability.SUPPLY_CENTER_STATE),
                "Missing evidence did not produce a skipped status");
        require(calls[0] == 0,
                "Investigator ran despite missing required evidence");

        TacticalContext partial = partialSupportContext();
        InvestigationReport incomplete =
                standard.investigateReport(partial);
        InvestigationCoverage.KindCoverage evaluated =
                coverage(incomplete, TacticKind.SUPPORT_TO_MOVE);

        require(evaluated.status()
                        == InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS
                        && evaluated.findingCount() == 1,
                "Incomplete local pattern was confused with incomplete evaluation");
        require(!incomplete.findings().getFirst().completePattern(),
                "Partial support pattern unexpectedly became complete");

        InvestigationReport filtered =
                incomplete.byKind(TacticKind.SELF_BOUNCE);

        require(filtered.findings().isEmpty(),
                "Filter unexpectedly retained findings of another kind");
        require(filtered.coverage().scope()
                        == InvestigationCoverage.Scope.FILTERED_VIEW,
                "Filtered report retained complete-investigation labeling");
        require(coverage(filtered, TacticKind.SUPPORT_TO_MOVE).findingCount() == 1
                        && coverage(filtered, TacticKind.SUPPORT_TO_MOVE).status()
                        == InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS,
                "Filtered report rewrote underlying coverage as a negative result");
        require(filtered.byParticipant(unit(
                        Nation.GERMANY, UnitType.ARMY, Province.Mun))
                        .coverage().scope()
                        == InvestigationCoverage.Scope.FILTERED_VIEW,
                "Chained filter discarded filtered coverage labeling");

    }


    // Assessment claims \\

    private static void assessmentContracts() {

        Set<TacticalAssessment.Claim> expected =
                TacticalAssessmentContracts.requiredClaims(
                        TacticKind.SUPPORT_TO_MOVE);

        require(expected.equals(EnumSet.of(
                        TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                        TacticalAssessment.Claim.SUPPORTED_MOVE_SUCCEEDED)),
                "Support-to-move assessment contract changed");
        require(TacticalAssessmentContracts.requiredClaims(
                        TacticKind.SUPPORT_TO_HOLD).equals(EnumSet.of(
                        TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                        TacticalAssessment.Claim.SUBJECT_SURVIVED)),
                "Support-to-hold assessment contract changed");
        require(TacticalAssessmentContracts.requiredClaims(
                        TacticKind.SELF_BOUNCE).equals(EnumSet.of(
                        TacticalAssessment.Claim.SELF_BOUNCE_REALIZED)),
                "Self-bounce assessment contract changed");
        require(TacticalAssessmentContracts.requiredClaims(
                        TacticKind.BELEAGUERED_GARRISON).equals(EnumSet.of(
                        TacticalAssessment.Claim.GARRISON_PRESERVED,
                        TacticalAssessment.Claim.COMPETITION_SAVED_GARRISON)),
                "Beleaguered-garrison assessment contract changed");

        TacticalContext complete = completeSupportContext();
        InvestigationReport report =
                new DetectiveAgency().investigateReport(complete);
        TacticMatch match = report.byKind(TacticKind.SUPPORT_TO_MOVE)
                .findings()
                .getFirst();
        TacticalScenario scenario = new TacticalScenario("baseline", complete);
        List<TacticalAssessment.Finding> claims =
                assessmentFindings(expected, scenario);

        TacticalAssessment assessment = new TacticalAssessment(
                match, scenario, "assessment-test-v1", claims);

        require(assessment.findings().size() == expected.size(),
                "A valid existing assessment contract was rejected");

        List<TacticalAssessment.Finding> missing = new ArrayList<>(claims);
        missing.removeFirst();
        rejects(IllegalArgumentException.class,
                () -> new TacticalAssessment(
                        match, scenario, "assessment-test-v1", missing),
                "Missing assessment claim");

        List<TacticalAssessment.Finding> duplicate = new ArrayList<>(claims);
        duplicate.add(claims.getFirst());
        rejects(IllegalArgumentException.class,
                () -> new TacticalAssessment(
                        match, scenario, "assessment-test-v1", duplicate),
                "Duplicate assessment claim");

        TacticalAssessment.Finding extra = new TacticalAssessment.Finding(
                TacticalAssessment.Claim.SELF_BOUNCE_REALIZED,
                TacticalAssessment.Truth.UNKNOWN,
                List.of(new TacticalAssessment.Evidence(
                        "UNKNOWN",
                        scenario,
                        "No finding contract authorizes this claim here.")));
        List<TacticalAssessment.Finding> unexpected = new ArrayList<>(claims);
        unexpected.add(extra);
        rejects(IllegalArgumentException.class,
                () -> new TacticalAssessment(
                        match, scenario, "assessment-test-v1", unexpected),
                "Unexpected assessment claim");

        TacticMatch unsupported = new TacticMatch(
                TacticKind.MULTIPLE_SUPPORT_TO_MOVE,
                "test-v1",
                complete,
                Province.Bur,
                List.of(new TacticMatch.Participant(
                        TacticMatch.Role.SUPPORTER,
                        unit(Nation.FRANCE, UnitType.ARMY, Province.Par))),
                Set.of());

        rejects(IllegalArgumentException.class,
                () -> new TacticalAssessment(
                        unsupported, scenario, "assessment-test-v1", List.of()),
                "Unsupported kind assessment");

    }


    // Input validation and immutable collections \\

    private static void validationAndImmutability() {

        Map<TacticKind, TacticDefinition> definitions =
                TacticDefinitionRegistry.definitions();
        TacticDefinition support =
                TacticDefinitionRegistry.require(TacticKind.SUPPORT_TO_MOVE);

        rejects(UnsupportedOperationException.class,
                () -> definitions.remove(TacticKind.SUPPORT_TO_MOVE),
                "Mutable definition registry");
        rejects(UnsupportedOperationException.class,
                () -> support.requiredEvidence().clear(),
                "Mutable definition evidence set");
        rejects(UnsupportedOperationException.class,
                () -> TacticalAssessmentContracts.supportedKinds().clear(),
                "Mutable assessment registry");
        rejects(UnsupportedOperationException.class,
                () -> TacticalAssessmentContracts.requiredClaims(
                        TacticKind.SUPPORT_TO_MOVE).clear(),
                "Mutable assessment claim set");

        InvestigationCoverage unimplemented =
                new DetectiveAgency().investigateReport(
                        context(Map.of(), Map.of())).coverage();

        rejects(UnsupportedOperationException.class,
                () -> unimplemented.byKind().clear(),
                "Mutable coverage list");
        rejects(NullPointerException.class,
                () -> TacticDefinitionRegistry.require(null),
                "Null definition query");
        rejects(NullPointerException.class,
                () -> TacticalAssessmentContracts.requiredClaims(null),
                "Null assessment query");
        rejects(NullPointerException.class,
                () -> new TacticDefinition(
                        null,
                        TacticFamily.SUPPORT_AND_COOPERATION,
                        TacticInterpretation.STRUCTURAL_PATTERN,
                        Set.of(EvidenceCapability.MOVEMENT_POSITION),
                        "v1",
                        "Semantics."),
                "Null definition kind");
        rejects(IllegalArgumentException.class,
                () -> new TacticDefinition(
                        TacticKind.SUPPORT_TO_MOVE,
                        TacticFamily.SUPPORT_AND_COOPERATION,
                        TacticInterpretation.STRUCTURAL_PATTERN,
                        Set.of(),
                        "v1",
                        "Semantics."),
                "Empty required-evidence set");
        rejects(NullPointerException.class,
                () -> new InvestigationCoverage(
                        InvestigationCoverage.Scope.NOT_RECORDED,
                        null),
                "Null coverage entries");
        rejects(IllegalArgumentException.class,
                () -> new InvestigationCoverage(
                        InvestigationCoverage.Scope.FILTERED_VIEW,
                        List.of(unimplemented.byKind().getFirst(),
                                unimplemented.byKind().getFirst())),
                "Duplicate coverage kind");
        rejects(NullPointerException.class,
                () -> new DetectiveAgency(Arrays.asList(
                        (TacticDetector) null)),
                "Null detective registration");
        rejects(NullPointerException.class,
                () -> new DetectiveAgency().investigateReport(null),
                "Null investigation context");

    }


    // Fixtures \\

    private static InvestigationCoverage.KindCoverage coverage(
            InvestigationReport report,
            TacticKind kind
    ) {

        return report.coverage().byKind().stream()
                .filter(entry -> entry.kind() == kind)
                .findFirst()
                .orElseThrow();

    }

    private static List<TacticalAssessment.Finding> assessmentFindings(
            Set<TacticalAssessment.Claim> claims,
            TacticalScenario scenario
    ) {

        List<TacticalAssessment.Finding> findings = new ArrayList<>();

        for (TacticalAssessment.Claim claim : claims)
            findings.add(new TacticalAssessment.Finding(
                    claim,
                    TacticalAssessment.Truth.UNKNOWN,
                    List.of(new TacticalAssessment.Evidence(
                            "UNKNOWN",
                            scenario,
                            "Required outcome evidence was not supplied."))));

        return findings;

    }

    private static TacticalContext partialSupportContext() {

        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId mar = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);

        return context(
                Map.of(par, Province.Par, mar, Province.Mar),
                Map.of(mar, known(Order.supportMove(
                        mar, Province.Par, Province.Bur))));

    }

    private static TacticalContext completeSupportContext() {

        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId mar = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);

        return context(
                Map.of(par, Province.Par, mar, Province.Mar),
                Map.of(
                        par, known(Order.move(par, Province.Bur)),
                        mar, known(Order.supportMove(
                                mar, Province.Par, Province.Bur))));

    }

    private static TacticalContext context(
            Map<UnitId, Province> locations,
            Map<UnitId, TacticalContext.KnownOrder> orders
    ) {

        return new TacticalContext(
                "architecture-self-check-v1",
                MOMENT,
                new BoardState(locations, Map.of()),
                orders);

    }

    private static TacticalContext.KnownOrder known(Order order) {
        return new TacticalContext.KnownOrder(
                order, TacticalContext.Provenance.SUBMITTED);
    }

    private static UnitId unit(
            Nation nation,
            UnitType type,
            Province origin
    ) {
        return new UnitId(nation, type, origin);
    }


    // Assertions \\

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }

    private static void rejects(
            Class<? extends Throwable> expected,
            Runnable action,
            String description
    ) {

        checks++;

        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure))
                return;

            throw new AssertionError(
                    description + " threw " + failure.getClass().getName(),
                    failure);
        }

        throw new AssertionError(description + " was accepted");

    }


}
