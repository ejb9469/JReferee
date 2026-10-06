package analysis.tactics;

import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Immutable findings from a structural tactical investigation.<br><br>
 *
 * Queries return new reports with the same context. Finding order is
 * preserved, allowing an agency's stable ordering to survive filtering.
 *
 * <p>A report may represent a complete investigation or a filtered selection.
 * An empty report does not prove that every tactical category was examined.</p>
 *
 * <p>Completeness refers to local pattern prerequisites, not scenario
 * completeness, adjudicated success, or strategic value.</p>
 */
public record InvestigationReport(
        TacticalContext context,
        List<TacticMatch> findings
) {


    // Construction and validation \\

    public InvestigationReport {

        Objects.requireNonNull(context, "context");

        findings = List.copyOf(
                Objects.requireNonNull(findings, "findings"));

        Set<TacticMatch> observed = new HashSet<>();

        for (TacticMatch match : findings) {

            // Preserve the original investigation context, not a reconstruction.
            if (match.context() != context)
                throw new IllegalArgumentException(
                        "Finding does not retain the supplied context");

            if (!observed.add(match))
                throw new IllegalArgumentException(
                        "Duplicate finding in investigation report");

        }

    }

    /**
     * Runs an agency and snapshots its findings into a report.<br><br>
     *
     * Querying the returned report does not run the detectives again.
     */
    public static InvestigationReport investigate(
            DetectiveAgency agency,
            TacticalContext context
    ) {

        Objects.requireNonNull(agency, "agency");
        Objects.requireNonNull(context, "context");

        return new InvestigationReport(
                context,
                agency.investigate(context));

    }


    // Basic queries \\

    public int size() {
        return findings.size();
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }


    // Category queries \\

    public InvestigationReport byKind(TacticKind kind) {

        Objects.requireNonNull(kind, "kind");

        List<TacticMatch> selected = new ArrayList<>();

        for (TacticMatch match : findings)
            if (match.kind() == kind)
                selected.add(match);

        return new InvestigationReport(context, selected);

    }


    // Participant queries \\

    /**
     * Selects findings containing the supplied concrete unit identity.<br><br>
     *
     * An unknown identity returns an empty report. Creation origin and
     * current location are not substitutes for unit identity.
     */
    public InvestigationReport byParticipant(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        List<TacticMatch> selected = new ArrayList<>();

        for (TacticMatch match : findings) {

            for (TacticMatch.Participant participant : match.participants()) {

                if (!participant.unit().equals(unit))
                    continue;

                // A unit occupying several roles still selects the match once.
                selected.add(match);
                break;

            }

        }

        return new InvestigationReport(context, selected);

    }


    // Territory queries \\

    /**
     * Selects findings whose primary tactical focus is this territory.<br><br>
     *
     * Split coasts are compared through their canonical province.
     */
    public InvestigationReport focusedOn(Province province) {

        Objects.requireNonNull(province, "province");

        Province territory = Province.canonical(province);
        List<TacticMatch> selected = new ArrayList<>();

        for (TacticMatch match : findings)
            if (sameTerritory(match.focus(), territory))
                selected.add(match);

        return new InvestigationReport(context, selected);

    }

    /**
     * Selects findings mentioning this territory through:<br><br>
     *
     * - the primary tactical focus;<br>
     * - a participant's actual board location;<br>
     * - a target or auxiliary target in a participant's known order.
     *
     * <p>Orders belonging to unrelated units are not inspected.
     * A territory mentioned through multiple paths selects the finding once.</p>
     *
     * <p>This is a reference query, not a tactical influence calculation.
     * It does not expand adjacency, infer convoy routes, or invent unknown
     * orders.</p>
     */
    public InvestigationReport atTerritory(Province province) {

        Objects.requireNonNull(province, "province");

        Province territory = Province.canonical(province);
        List<TacticMatch> selected = new ArrayList<>();

        for (TacticMatch match : findings)
            if (mentionsTerritory(match, territory))
                selected.add(match);

        return new InvestigationReport(context, selected);

    }


    // Completeness queries \\

    public InvestigationReport completePatterns() {
        return withCompleteness(true);
    }

    public InvestigationReport incompletePatterns() {
        return withCompleteness(false);
    }

    /**
     * Selects patterns by local prerequisite completeness.<br><br>
     *
     * A complete pattern may still belong to a context with unknown
     * surrounding orders.
     */
    public InvestigationReport withCompleteness(boolean complete) {

        List<TacticMatch> selected = new ArrayList<>();

        for (TacticMatch match : findings)
            if (match.completePattern() == complete)
                selected.add(match);

        return new InvestigationReport(context, selected);

    }


    // Territory helpers \\

    private boolean mentionsTerritory(
            TacticMatch match,
            Province territory
    ) {

        if (sameTerritory(match.focus(), territory))
            return true;

        for (TacticMatch.Participant participant : match.participants()) {

            UnitId unit = participant.unit();

            if (sameTerritory(context.board().locationOf(unit), territory))
                return true;

            TacticalContext.KnownOrder evidence = context.orders().get(unit);

            if (evidence == null)
                continue;

            Order order = evidence.order();

            if (sameTerritory(order.target(), territory)
                    || sameTerritory(order.auxiliaryTarget(), territory))
                return true;

        }

        return false;

    }

    private static boolean sameTerritory(
            Province province,
            Province territory
    ) {
        return province != null && Province.canonical(province) == territory;
    }


}