package _app;

import _app.util.AbstractDiploBNConsoleApp;
import analysis.tactics.*;
import contracts.OrderForm;
import game.GameMoment;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;


/**
 * Temporary console inspector for tactical patterns in one DiploBN game.<br><br>
 *
 * Downloads the game, examines each movement snapshot independently, and
 * prints structural findings with investigation coverage.
 *
 * <p>This application does not adjudicate orders, replay a live Game,
 * or establish that a recognized tactic succeeded.</p>
 *
 * <p>Missing source submissions remain UNKNOWN. Retreat and adjustment
 * snapshots are not passed to the movement-only tactical context.</p>
 */
public final class DBNTacticsApp extends AbstractDiploBNConsoleApp {


    // Constants \\

    /*
     * Structural-import context identifier only.
     * This is not a claim about DBN's adjudication or paradox policy.
     */
    private static final String RULESET_ID =
            "diplobn-standard-structural-inspection-v1";


    // Construction \\

    private DBNTacticsApp() {  }


    // Application entry point \\

    public static void main(String[] args)
            throws IOException, InterruptedException {

        new DBNTacticsApp().run(args);

    }

    private void run(String[] args)
            throws IOException, InterruptedException {

        if (args.length > 1)
            throw new IllegalArgumentException(
                    "Usage: DBNTacticsApp [https://diplobn.com/game/?GameID=<id>]");

        String gamePageUrl = requestedGamePageUrl(args);

        if (gamePageUrl == null)
            return;

        gamePageUrl = gamePageUrl.strip();

        System.out.println();
        System.out.println("[DISPATCH] Downloading DiploBN game...");
        System.out.println("Requested URL: " + gamePageUrl);

        DiploBNGame imported;

        try {

            imported = new DiploBNGameClient().loadGamePage(gamePageUrl);

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();
            throw exception;

        }

        printReport(imported, System.out);

    }


    // Game investigation \\

    /**
     * Inspects an already-parsed game without performing network requests.<br><br>
     *
     * This entry point can also be used with DiploBNParser fixtures.
     * The caller owns the output stream; this method does not close it.
     */
    public static void printReport(
            DiploBNGame imported,
            PrintStream output
    ) {

        Objects.requireNonNull(imported, "imported");
        Objects.requireNonNull(output, "output");

        DetectiveAgency agency = new DetectiveAgency();

        Map<TacticKind, Totals> totals = new EnumMap<>(TacticKind.class);

        for (TacticKind kind : agency.kinds())
            totals.put(kind, new Totals());

        printHeader(imported, agency, output);

        int movementSnapshots = 0;
        int investigatedSnapshots = 0;
        int rejectedSnapshots = 0;
        int skippedSnapshots = 0;
        int partialSnapshots = 0;
        int emptyOrderSnapshots = 0;

        int sourceIndex = 0;

        /*
         * Preserve source order and identify each snapshot separately.
         * Do not silently combine duplicate timestamps or infer unit
         * lifetimes across independently imported board snapshots.
         */
        for (DiploBNPhase phase : imported.phases()) {

            sourceIndex++;

            if (!phase.gamePhase().isMovement()) {
                skippedSnapshots++;
                continue;
            }

            movementSnapshots++;

            output.println();
            output.println("----------------------------------------");
            output.printf(
                    "[CASE %d] %s | %s | source status: %s%n",
                    sourceIndex,
                    formatSeasonalSourcePhase(phase.sourcePhase()),
                    phase.gamePhase(),
                    phase.sourceStatus());

            TacticalContext context;

            try {

                context = contextOf(phase);

            } catch (IllegalArgumentException exception) {

                rejectedSnapshots++;

                output.println(
                        "  [REJECTED INPUT] " + exception.getMessage());

                output.println(
                        "  This snapshot was not investigated; "
                                + "it contributes no negative findings.");

                continue;

            }

            /*
             * Detective contract failures are not source-data omissions.
             * Let them propagate rather than disguising them as an empty
             * or partially successful investigation.
             */
            InvestigationReport report = agency.investigateReport(context);

            investigatedSnapshots++;

            if (!context.complete())
                partialSnapshots++;

            if (context.orders().isEmpty())
                emptyOrderSnapshots++;

            printSnapshot(report, output);
            accumulate(report, totals);

        }

        output.println();
        output.println("========================================");
        output.println("INVESTIGATION SUMMARY");
        output.println("========================================");

        output.printf(
                "Source snapshots:                 %d%n",
                imported.phases().size());

        output.printf(
                "Movement snapshots:               %d%n",
                movementSnapshots);

        output.printf(
                "Successfully investigated:         %d%n",
                investigatedSnapshots);

        output.printf(
                "Rejected movement inputs:          %d%n",
                rejectedSnapshots);

        output.printf(
                "Skipped retreat/adjustment inputs: %d%n",
                skippedSnapshots);

        output.printf(
                "Investigated with unknown orders:  %d%n",
                partialSnapshots);

        output.printf(
                "Investigated with no known orders: %d%n",
                emptyOrderSnapshots);

        output.println();
        output.println(
                "Counts below are occurrences per source snapshot, "
                        + "not unique tactics across the game.");

        output.printf(
                "%-28s %9s %9s %9s %9s %9s%n",
                "KIND", "EVALUATED", "SKIPPED", "FINDINGS", "COMPLETE", "PARTIAL");

        for (Map.Entry<TacticKind, Totals> entry : totals.entrySet()) {

            Totals total = entry.getValue();

            output.printf(
                    "%-28s %9d %9d %9d %9d %9d%n",
                    entry.getKey(),
                    total.evaluated,
                    total.skipped,
                    total.findings,
                    total.complete,
                    total.incomplete);

        }

        int unsupported = TacticKind.values().length - agency.kinds().size();

        output.printf(
                "%nCatalogue kinds without a registered detective: %d%n",
                unsupported);

        output.println(
                "Unsupported kinds were NOT tested, not found absent.");

        if (investigatedSnapshots == 0)
            output.println(
                    "[NO EVIDENCE] No movement snapshot was successfully investigated.");

        output.println(
                "Structural completeness is not proof of legality, "
                        + "successful execution, or strategic value.");

    }


    // Context conversion \\

    private static TacticalContext contextOf(DiploBNPhase phase) {

        Map<UnitId, TacticalContext.KnownOrder> known = new LinkedHashMap<>();

        for (Order order : phase.movementOrders()) {

            TacticalContext.KnownOrder evidence =
                    new TacticalContext.KnownOrder(
                            order,
                            TacticalContext.Provenance.SUBMITTED);

            if (known.putIfAbsent(order.unit(), evidence) != null)
                throw new IllegalArgumentException(
                        "Multiple source orders for "
                                + OrderForm.unitText(
                                order.unit(),
                                phase.board().locationOf(order.unit())));

        }

        /*
         * DiploBN encodes year and season as YYYY1 / YYYY2 / YYYY3.
         * The parser supplies the actual movement/retreat/adjustment phase.
         */
        GameMoment moment = new GameMoment(
                phase.sourcePhase() / 10,
                phase.gamePhase());

        return new TacticalContext(
                RULESET_ID,
                moment,
                phase.board(),
                known);

    }


    // Header output \\

    private static void printHeader(
            DiploBNGame imported,
            DetectiveAgency agency,
            PrintStream output
    ) {

        output.println();
        output.println("========================================");
        output.println("DIPLOBN TACTICAL INVESTIGATION");
        output.println("========================================");

        output.println("Game: " + text(imported.gameLabel()));
        output.println("Competition: " + text(imported.competition()));
        output.println("Source metadata URL: " + text(imported.sourceUrl()));

        output.println("Registered detectives: " + agency.kinds());

        output.printf(
                "Catalogue: %d kinds; %d registered investigators.%n",
                TacticKind.values().length,
                agency.kinds().size());

        output.println();
        output.println(
                "Findings describe translated source submissions, "
                        + "not adjudicated outcomes.");

        output.println(
                "Historical success/failure annotations are not used "
                        + "to confirm tactical effects.");

        output.println(
                "Missing orders remain UNKNOWN; no HOLD orders are invented.");

    }


    // Snapshot output \\

    private static void printSnapshot(
            InvestigationReport report,
            PrintStream output
    ) {

        TacticalContext context = report.context();
        List<UnitId> unknown = context.unknownUnits();

        output.printf(
                "  Active units: %d | Known submissions: %d | Unknown orders: %d%n",
                context.board().locations().size(),
                context.orders().size(),
                unknown.size());

        if (!unknown.isEmpty()) {

            StringJoiner descriptions = new StringJoiner("; ");

            for (UnitId unit : unknown)
                descriptions.add(unitText(context, unit));

            output.println("  Unknown: " + descriptions);

        }

        output.printf(
                "  Findings: %d | Complete patterns: %d | Incomplete patterns: %d%n",
                report.size(),
                report.completePatterns().size(),
                report.incompletePatterns().size());

        if (context.orders().isEmpty())
            output.println(
                    "  [LIMITED EVIDENCE] No submissions are known in this snapshot.");

        if (report.isEmpty())
            output.println(
                    "  No structural matches in the supplied evidence. "
                            + "This is not a tactical-safety verdict.");

        int number = 0;

        for (TacticMatch match : report.findings())
            printMatch(++number, match, output);

        printCoverage(report.coverage(), output);

    }

    private static void printMatch(
            int number,
            TacticMatch match,
            PrintStream output
    ) {

        TacticalContext context = match.context();

        output.printf(
                "%n  [FINDING %d] %s at %s [%s]%n",
                number,
                match.kind(),
                match.focus(),
                match.completePattern() ? "COMPLETE PATTERN" : "INCOMPLETE PATTERN");

        for (TacticMatch.Participant participant : match.participants()) {

            UnitId unit = participant.unit();
            TacticalContext.KnownOrder evidence = context.orders().get(unit);

            String description = evidence == null
                    ? unitText(context, unit) + " | ORDER UNKNOWN"
                    : OrderForm.format(
                    evidence.order(),
                    context.board().locationOf(unit))
                    + " | " + evidence.provenance();

            output.printf(
                    "    %-16s %s%n",
                    participant.role(),
                    description);

        }

        if (!match.missingOrders().isEmpty()) {

            List<UnitId> missing = new ArrayList<>(match.missingOrders());

            missing.sort(Comparator.comparing(
                    unit -> context.board().locationOf(unit).name()));

            for (UnitId unit : missing)
                output.println(
                        "    Missing prerequisite: order for "
                                + unitText(context, unit));

        }

    }


    // Coverage output \\

    private static void printCoverage(
            InvestigationCoverage coverage,
            PrintStream output
    ) {

        output.println();
        output.println("  Coverage: " + coverage.scope());

        int unsupported = 0;

        for (InvestigationCoverage.KindCoverage entry : coverage.byKind()) {

            if (entry.status()
                    == InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED) {

                unsupported++;
                continue;

            }

            output.printf(
                    "    %-28s %-30s findings=%d%n",
                    entry.kind(),
                    entry.status(),
                    entry.findingCount());

            if (!entry.missingEvidence().isEmpty())
                output.println(
                        "      Missing capabilities: " + entry.missingEvidence());

        }

        output.printf(
                "    Unsupported/unimplemented: %d kinds (not evaluated).%n",
                unsupported);

    }


    // Aggregation \\

    private static void accumulate(
            InvestigationReport report,
            Map<TacticKind, Totals> totals
    ) {

        for (InvestigationCoverage.KindCoverage coverage
                : report.coverage().byKind()) {

            if (!coverage.implemented())
                continue;

            Totals total = totals.computeIfAbsent(
                    coverage.kind(), ignored -> new Totals());

            switch (coverage.status()) {

                case EVALUATED_WITH_FINDINGS, EVALUATED_WITH_NO_FINDINGS ->
                        total.evaluated++;

                case SKIPPED_MISSING_EVIDENCE ->
                        total.skipped++;

                case UNSUPPORTED_UNIMPLEMENTED ->
                        throw new IllegalStateException(
                                "Implemented kind reported as unsupported");

            }

        }

        for (TacticMatch match : report.findings()) {

            Totals total = totals.computeIfAbsent(
                    match.kind(), ignored -> new Totals());

            total.findings++;

            if (match.completePattern())
                total.complete++;
            else
                total.incomplete++;

        }

    }


    // Formatting helpers \\

    private static String unitText(
            TacticalContext context,
            UnitId unit
    ) {
        return OrderForm.unitText(unit, context.board().locationOf(unit));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "<not supplied>" : value;
    }


    // Summary state \\

    private static final class Totals {

        private long evaluated;
        private long skipped;
        private long findings;
        private long complete;
        private long incomplete;

    }


}