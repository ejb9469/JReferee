package testing.selfcheck;

import analysis.tactics.*;
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
 * Checks the complete default registration independently of legacy fixtures.
 */
public final class DefaultDetectivesSelfCheck {


    // Constants \\

    private static final Set<TacticKind> IMPLEMENTED = EnumSet.of(
            TacticKind.SUPPORT_TO_MOVE,
            TacticKind.SUPPORT_TO_HOLD,
            TacticKind.SELF_BOUNCE,
            TacticKind.BELEAGUERED_GARRISON,
            TacticKind.MULTIPLE_SUPPORT_TO_MOVE,
            TacticKind.MULTIPLE_SUPPORT_TO_HOLD,
            TacticKind.MUTUAL_HOLD_SUPPORT,
            TacticKind.SUPPORT_NETWORK,
            TacticKind.SUPPORTING_A_SUPPORTER,
            TacticKind.SUPPORTING_A_CONVOY_FLEET,
            TacticKind.CROSS_POWER_SUPPORT,
            TacticKind.MULTINATIONAL_SUPPORTED_ATTACK,
            TacticKind.CROSS_POWER_CONTEST,
            TacticKind.MULTIWAY_CONTEST,
            TacticKind.HEAD_TO_HEAD_CANDIDATE,
            TacticKind.CIRCULAR_MOVEMENT,
            TacticKind.FOLLOW_THE_LEADER,
            TacticKind.CHAIN_ADVANCE,
            TacticKind.FRIENDLY_OCCUPANT_COLLISION,
            TacticKind.VACATE_AND_REPLACE,
            TacticKind.ATTACK_ON_SUPPORTER,
            TacticKind.ATTACK_FROM_SUPPORTED_DESTINATION,
            TacticKind.CONVOYED_MOVE,
            TacticKind.MULTI_FLEET_CONVOY,
            TacticKind.MULTI_ROUTE_CONVOY,
            TacticKind.FOREIGN_CONVOY,
            TacticKind.MULTINATIONAL_CONVOY,
            TacticKind.SUPPORTED_CONVOY_LANDING,
            TacticKind.CONVOY_SWAP,
            TacticKind.ADJACENT_PROVINCE_CONVOY,
            TacticKind.ATTACK_ON_CONVOY_FLEET,
            TacticKind.UNMATCHED_CONVOY_ORDER,
            TacticKind.INCOMPLETE_CONVOY_CHAIN,
            TacticKind.SUPPORT_ORDER_MISMATCH,
            TacticKind.FOREIGN_COOPERATION_DEPENDENCY,
            TacticKind.BOGUS_MOVES);

    private static final Set<EvidenceCapability> AVAILABLE = EnumSet.of(
            EvidenceCapability.MOVEMENT_POSITION,
            EvidenceCapability.KNOWN_MOVEMENT_ORDERS,
            EvidenceCapability.MAP_ADJACENCY);


    // Check state \\

    private static int checks;


    // Construction \\

    private DefaultDetectivesSelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        DetectiveAgency agency = new DetectiveAgency();

        require(new HashSet<>(agency.kinds()).equals(IMPLEMENTED),
                "Default registration differs from implemented catalogue");

        UnitId mover = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Bre);
        UnitId supporter = new UnitId(
                UUID.randomUUID(), Nation.GERMANY, UnitType.ARMY, Province.Ber);

        BoardState board = new BoardState(
                Map.of(mover, Province.Par, supporter, Province.Gas),
                Map.of());

        GameMoment moment = new GameMoment(1902, GamePhase.SPRING_MOVEMENT);

        TacticalContext unknown = new TacticalContext(
                "default-self-check", moment, board, Map.of());

        TacticalContext known = new TacticalContext(
                "default-self-check", moment, board,
                Map.of(
                        mover, evidence(Order.move(mover, Province.Bur)),
                        supporter, evidence(Order.supportMove(
                                supporter, Province.Par, Province.Bur))));

        verifyCoverage(agency.investigateReport(unknown), true);
        verifyCoverage(agency.investigateReport(known), false);

        InvestigationReport report = agency.investigateReport(known);

        require(report.findings().stream().map(TacticMatch::kind).toList()
                        .equals(List.of(
                                TacticKind.SUPPORT_TO_MOVE,
                                TacticKind.CROSS_POWER_SUPPORT,
                                TacticKind.FOREIGN_COOPERATION_DEPENDENCY)),
                "Default fixture produced an unexpected complete finding set");

        Set<TacticMatch.Participant> participants = Set.of(
                new TacticMatch.Participant(TacticMatch.Role.SUPPORTER, supporter),
                new TacticMatch.Participant(TacticMatch.Role.SUPPORTED_UNIT, mover));

        for (TacticMatch finding : report.findings())
            require(finding.focus() == Province.Bur
                            && new HashSet<>(finding.participants()).equals(participants)
                            && finding.missingOrders().isEmpty(),
                    "Default finding changed focus, roles, or local completeness");

        require(report.byKind(TacticKind.SUPPORT_TO_MOVE).size() == 1,
                "Default agency omitted the known support");
        require(report.byKind(TacticKind.CROSS_POWER_SUPPORT).size() == 1,
                "Default agency omitted the overlapping foreign support");
        require(report.byKind(TacticKind.FOREIGN_COOPERATION_DEPENDENCY).size() == 1,
                "Default agency omitted candidate cooperation evidence");

        require(agency.investigate(known).equals(report.findings()),
                "List API differs from report API");
        require(agency.investigate(unknown).isEmpty(),
                "Default detectives retained earlier findings");
        require(agency.investigate(known).equals(report.findings()),
                "Reuse changed the complete result set");

        Set<TacticKind> deferred = EnumSet.allOf(TacticKind.class);
        deferred.removeAll(IMPLEMENTED);

        System.out.printf(
                "Default detectives self-check passed: %d checks; "
                        + "%d catalogue, %d registered, %d deferred.%n",
                checks, TacticKind.values().length,
                agency.kinds().size(), deferred.size());

    }


    // Whole-catalogue coverage \\

    private static void verifyCoverage(
            InvestigationReport report,
            boolean empty
    ) {

        require(report.coverage().scope()
                        == InvestigationCoverage.Scope.COMPLETE_INVESTIGATION,
                "Default report is not a whole investigation");
        require(report.coverage().byKind().size() == TacticKind.values().length,
                "Coverage omitted a catalogue kind");
        require(new HashSet<>(report.findings()).size() == report.size(),
                "Default agency returned duplicate findings");

        Set<TacticKind> observed = EnumSet.noneOf(TacticKind.class);

        for (InvestigationCoverage.KindCoverage entry
                : report.coverage().byKind()) {

            TacticKind kind = entry.kind();
            require(observed.add(kind), "Duplicate coverage entry");

            TacticDefinition definition = TacticDefinitionRegistry.require(kind);
            boolean implemented = IMPLEMENTED.contains(kind);

            require(entry.implemented() == implemented,
                    "Wrong implementation status for " + kind);
            require(entry.applicable() == implemented,
                    "Wrong applicability for " + kind);

            if (!implemented) {

                require(entry.status()
                                == InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED,
                        "Deferred kind presented as evaluated: " + kind);
                continue;

            }

            require(AVAILABLE.containsAll(definition.requiredEvidence()),
                    "Registered kind requires unavailable evidence: " + kind);

            List<TacticMatch> findings = report.byKind(kind).findings();

            require(entry.findingCount() == findings.size(),
                    "Coverage count differs from findings: " + kind);
            require(entry.missingEvidence().isEmpty(),
                    "Structural kind unexpectedly skipped: " + kind);
            require(entry.status() == (findings.isEmpty()
                            ? InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS
                            : InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS),
                    "Wrong evaluated status for " + kind);

            if (empty)
                require(findings.isEmpty(),
                        "Unknown orders fabricated a finding: " + kind);

            for (TacticMatch finding : findings) {

                require(finding.context() == report.context(),
                        "Finding lost source context");
                require(!finding.detectorVersion().isBlank()
                                && !definition.semanticVersion().isBlank(),
                        "Finding or definition omitted version");

            }

        }

        require(observed.equals(EnumSet.allOf(TacticKind.class)),
                "Coverage is not the exact catalogue");

    }


    // Helpers \\

    private static TacticalContext.KnownOrder evidence(Order order) {
        return new TacticalContext.KnownOrder(
                order, TacticalContext.Provenance.SUBMITTED);
    }

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }


}
