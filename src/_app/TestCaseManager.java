package _app;

import adjudication.*;
import adjudication.Order;
import domain.OrderType;
import domain.Province;
import parsing.datc.DATCFileParser;
import parsing.datc.DATCProcessorFileParser;
import parsing.datc.FileTestCaseParser;
import testing.*;
import domain.Constants;
import adjudication.util.OrderComparator;
import adjudication.util.Orders;

import java.util.*;


/**
 * Owns a collection of adjudication test cases.
 * Provides runners for `Judge`, `Justice`, and `SzykmanJustice` evaluation.
 *
 * @author Evan B
 */
public class TestCaseManager {


    // Constants \\

    // MODE 0: `Justice.java` implementation
    // MODE 1: pre-Justice implementation
    public static final short MODE = 0;

    /*
     * Select the DATC/Szykman-oriented referee policy for diagnostics and any
     * Justice instances constructed directly by this class.
     *
     * MODE 0 is evaluated by TestCaseReferee, which constructs its own Judge /
     * Justice instance. Update TestCaseReferee separately to make normal
     * one-off test evaluation use this policy too.
     */
    public static final boolean USE_SZYKMAN_REFEREE = true;

    /*
     * Limit printed provenance samples per candidate. The complete candidate
     * frequency remains available through occurrences and seed counts.
     */
    private static final int MAX_PROVENANCE_SAMPLES = 12;


    // Core state \\

    protected final List<AdjudicatorTestCase>       adjudicatorTestCases;
    protected final List<ProcessorTestCase<?,?>>    processorTestCases;
    protected final List<TestCase>                  parserTestCases;

    protected final boolean prints;


    // Constructors \\

    public TestCaseManager() {
        this(true);
    }

    public TestCaseManager(boolean willPrint) {
        this.adjudicatorTestCases = new ArrayList<>();
        this.processorTestCases = new ArrayList<>();
        this.parserTestCases = new ArrayList<>();
        this.prints = willPrint;
    }


    // Public accessors \\

    public int score() {

        int score = 0;

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases) {
            if (testCase.getScore() == testCase.getOrders().size())
                score++;
        }

        return score;

    }

    public int size() {
        return this.adjudicatorTestCases.size();
    }

    public int ordersScore() {

        int score = 0;

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases)
            score += testCase.getScore();

        return score;

    }

    public int ordersSize() {

        int size = 0;

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases)
            size += testCase.getOrders().size();

        return size;

    }

    public int processorScore() {

        int score = 0;

        for (ProcessorTestCase<?,?> testCase : this.processorTestCases) {
            if (testCase.getScore() == testCase.getSize())
                score++;
        }

        return score;

    }

    public int processorSize() {
        return this.processorTestCases.size();
    }

    public int processorChecksScore() {

        int score = 0;

        for (ProcessorTestCase<?,?> testCase : this.processorTestCases)
            score += testCase.getScore();

        return score;

    }

    public int processorChecksSize() {

        int size = 0;

        for (ProcessorTestCase<?,?> testCase : this.processorTestCases)
            size += testCase.getSize();

        return size;

    }

    public int parserScore() {

        int score = 0;

        for (TestCase testCase : this.parserTestCases) {
            if (testCase.getScore() == testCase.getSize())
                score++;
        }

        return score;

    }

    public int parserSize() {
        return this.parserTestCases.size();
    }

    public int parserChecksScore() {

        int score = 0;

        for (TestCase testCase : this.parserTestCases)
            score += testCase.getScore();

        return score;

    }

    public int parserChecksSize() {

        int size = 0;

        for (TestCase testCase : this.parserTestCases)
            size += testCase.getSize();

        return size;

    }

    public List<AdjudicatorTestCase> getAdjudicatorTestCases() {
        return this.adjudicatorTestCases;
    }

    public List<ProcessorTestCase<?,?>> getProcessorTestCases() {
        return this.processorTestCases;
    }

    public List<TestCase> getParserTestCases() {
        return this.parserTestCases;
    }

    public boolean willPrint() {
        return this.prints;
    }


    // Test case management \\

    public void addProcessorTestCase(ProcessorTestCase<?,?> testCase) {
        this.processorTestCases.add(testCase);
    }

    public void addProcessorTestCases(Collection<? extends ProcessorTestCase<?,?>> testCases) {
        this.processorTestCases.addAll(testCases);
    }

    public void addParserTestCase(TestCase testCase) {
        this.parserTestCases.add(testCase);
    }

    public void addParserTestCases(
            Collection<? extends TestCase> testCases
    ) {
        this.parserTestCases.addAll(testCases);
    }

    public void addAdjudicatorTestCase(AdjudicatorTestCase testCase) {
        this.adjudicatorTestCases.add(testCase);
    }

    public void addAdjudicatorTestCases(Collection<? extends AdjudicatorTestCase> testCases) {
        this.adjudicatorTestCases.addAll(testCases);
    }

    public void addAdjudicatorTestCaseWithFields(
            AdjudicatorTestCase testCase,
            boolean evalNow,
            boolean... expectedFields
    ) {

        testCase.setExpectedFields(expectedFields);
        this.adjudicatorTestCases.add(testCase);

        if (evalNow)
            testCase.eval(this.prints);

    }

    public void addAdjudicatorTestCaseWithFields(
            AdjudicatorTestCase testCase,
            boolean evalNow,
            boolean[]... expectedFields
    ) {

        testCase.setExpectedFields(expectedFields);
        this.adjudicatorTestCases.add(testCase);

        if (evalNow)
            testCase.eval(this.prints);

    }


    // Test execution \\

    public void runDiagnostics() {

        /*
         * Temporary diagnostics for all currently unresolved / unstable cases.
         *
         * Remove this invocation after the investigation, but keep
         * diagnoseRefereeStability(...) for future regressions.
         */
        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases) {

            String name = testCase.getName();

            // PLACE DIAGNOSTIC FUNCTIONS HERE \\



        }

    }

    public void runJusticeTests() {

        System.out.println("REFEREE ONE-OFF TESTING:\n");

        if (USE_SZYKMAN_REFEREE)
            SzykmanJustice.resetProbeDiagnostics();

        List<DATCAdjTestCase> refTCs = new ArrayList<>();

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases) {
            DATCAdjTestCase refTC =
                    new DATCAdjTestCase(testCase);
            refTCs.add(refTC);
            refTC.eval(this.willPrint());
        }

        this.printTestCaseResults(refTCs);

        this.adjudicatorTestCases.clear();
        this.adjudicatorTestCases.addAll(refTCs);

        this.printTotals();

        if (USE_SZYKMAN_REFEREE)
            this.printProbeDiagnostics();

    }

    public void runJudgeSimulationTests() {

        int NUM_TRIALS = Justice.NUM_TRIALS_DEFAULT;

        Map<AdjudicatorTestCase, Collection<Set<Order>>> refereeSimul =
                new HashMap<>(this.adjudicatorTestCases.size());

        Collection<Set<Order>> permutations;

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases) {

            permutations = new HashSet<>();

            for (int i = 1; i <= NUM_TRIALS; i++) {
                AdjudicatorTestCase testCaseClone = new AdjudicatorTestCase(testCase);
                testCaseClone.shuffle();
                testCaseClone.eval();
                permutations.add(new HashSet<>(Set.copyOf(
                        Orders.deepCopy(testCaseClone.getOrders()))));
            }

            refereeSimul.put(testCase, permutations);

        }

        System.out.println("REFEREE SIMUL TESTING:\n");

        for (AdjudicatorTestCase testCase : refereeSimul.keySet()) {
            System.out.printf(
                    "[P=%d]\t%s%n",
                    refereeSimul.get(testCase).size(),
                    testCase.getName());
        }

        System.out.println("\n----------------------------------------");
        System.out.println(
                "REFEREE SIMUL TESTING - PARADOX CASES:\n");

        Map<AdjudicatorTestCase, Collection<Set<Order>>>
                refereeSimulParadoxes = new HashMap<>();

        for (AdjudicatorTestCase testCase : refereeSimul.keySet()) {
            if (refereeSimul.get(testCase).size() > 1) {
                refereeSimulParadoxes.put(
                        testCase,
                        refereeSimul.get(testCase));
                System.out.printf(
                        "[P=%d]\t%s%n",
                        refereeSimul.get(testCase).size(),
                        testCase.getName());
            }
        }

        System.out.printf(
                "%nTOTAL # PARADOXES: [%d]%n",
                refereeSimulParadoxes.size());

        System.out.println("----------------------------------------\n");

        Justice ref;

        for (AdjudicatorTestCase paradox : refereeSimulParadoxes.keySet()) {

            ref = createReferee(paradox.getOrders());
            ref.judge();

            System.out.println(paradox.getName());

            for (Order order : ref.getOrders()) {
                System.out.println(
                        "\t" + order + ":\n\t\t"
                                + order.metaToString());
            }

            System.out.println();

        }

        System.out.println("----------------------------------------\n");
        System.out.println("ONE-OFF (Judge) TESTING:\n");

        for (AdjudicatorTestCase testCase : this.adjudicatorTestCases)
            testCase.eval(this.willPrint());

        this.printTestCaseResults(this.adjudicatorTestCases);
        this.printTotals();

    }

    public void runProcessorTests() {

        System.out.println("PHASE PROCESSOR TESTING:\n");

        for (ProcessorTestCase<?,?> testCase : this.processorTestCases)
            testCase.eval(false);

        this.printProcessorTestCaseResults(this.processorTestCases);

        this.printProcessorTotals();

    }

    public void runParserTests() {

        System.out.println("PARSER TESTING:\n");

        for (TestCase testCase : this.parserTestCases)
            testCase.eval(false);

        this.printParserTestCaseResults(this.parserTestCases);

        this.printParserTotals();

    }


    // Reporting helpers \\

    private void printTestCaseResults(Collection<? extends AdjudicatorTestCase> testCases) {

        //System.out.println("----------------------------------------\n");

        for (AdjudicatorTestCase testCase : testCases) {
            testCase.printNameAndScore();
            if (testCase.getScore() != testCase.getSize())
                System.out.println(
                        Constants.ANSI_RED
                                + "\tFAILED!!"
                                + Constants.ANSI_RESET);
        }

    }

    private void printProcessorTestCaseResults(Collection<? extends ProcessorTestCase<?,?>> testCases) {

        //System.out.println("----------------------------------------\n");

        for (ProcessorTestCase<?,?> testCase : testCases) {
            testCase.printNameAndScore();
            if (testCase.getScore() != testCase.getSize())
                System.out.println(
                        Constants.ANSI_RED
                                + "\tFAILED!!"
                                + Constants.ANSI_RESET);
        }

    }

    private void printParserTestCaseResults(Collection<? extends TestCase> testCases) {

        //System.out.println("----------------------------------------\n");

        for (TestCase testCase : testCases) {
            testCase.printNameAndScore();
            if (testCase.getScore() != testCase.getSize())
                System.out.println(
                        Constants.ANSI_RED
                                + "\tFAILED!!"
                                + Constants.ANSI_RESET);
        }

    }

    private void printTotals() {

        System.out.println("\n----------------------------------------");
        System.out.printf(
                "TOTAL SCORE (by Test Cases):\t[%d/%d]%n",
                this.score(),
                this.size());
        System.out.printf(
                "TOTAL SCORE (by Orders):\t\t[%d/%d]%n",
                this.ordersScore(),
                this.ordersSize());
        System.out.println("----------------------------------------\n");

    }

    private void printProcessorTotals() {

        System.out.println("\n----------------------------------------");
        System.out.printf(
                "TOTAL SCORE (by Test Cases):\t[%d/%d]%n",
                this.processorScore(),
                this.processorSize());
        System.out.printf(
                "TOTAL SCORE (by Checks):\t\t[%d/%d]%n",
                this.processorChecksScore(),
                this.processorChecksSize());
        System.out.println("----------------------------------------\n");

    }

    private void printParserTotals() {

        System.out.println("\n----------------------------------------");
        System.out.printf(
                "TOTAL SCORE (by Test Cases):\t[%d/%d]%n",
                this.parserScore(),
                this.parserSize());
        System.out.printf(
                "TOTAL SCORE (by Checks):\t\t[%d/%d]%n",
                this.parserChecksScore(),
                this.parserChecksSize());
        System.out.println("----------------------------------------\n");

    }

    private void printProbeDiagnostics() {

        SzykmanJustice.ProbeDiagnostics diagnostics =
                SzykmanJustice.getProbeDiagnostics();

        System.out.println("SZYKMAN PROBE DIAGNOSTICS:\n");

        System.out.printf(
                "Final SzykmanJustice selections:\t\t\t%d%n",
                diagnostics.finalResolutionSelections());
        System.out.printf(
                "Multi-convoy candidates (2+ conflicts):\t\t%d%n",
                diagnostics.multiConvoyCandidates());
        System.out.printf(
                "Jury invocations:\t\t%d%n",
                diagnostics.ordinaryResolutionProbeInvocations());
        System.out.printf(
                "Complete ordinary probes:\t\t\t%d%n",
                diagnostics.completeOrdinaryResolutionProbes());
        System.out.printf(
                "Matching ordinary candidates selected:\t\t%d%n",
                diagnostics.ordinaryCandidateSelections());
        System.out.printf(
                "Szykman simultaneous-HOLD fallbacks:\t\t%d%n",
                diagnostics.szykmanFallbacks());
        System.out.println();

    }


    // Application entry point \\

    public static void main(String[] args) {

        System.out.println();
        Constants.printTimestamp();

        TestCaseManager manager = new TestCaseManager(true);
        FileTestCaseParser fileParser = new DATCFileParser();

        manager.addAdjudicatorTestCases(fileParser.parseManyFiles());
        //manager.runDiagnostics();

        System.out.println("\n----------------------------------------\n");

        DATCProcessorFileParser processorFileParser =
                new DATCProcessorFileParser();

        manager.addProcessorTestCases(
                processorFileParser.parseManyFiles());

        manager.addParserTestCase(
                new DiploBNParserTestCase());

        manager.addParserTestCase(
                new CatalogTestCase());

        System.out.println("\n----------------------------------------\n");

        switch (MODE) {

            case 0 -> manager.runJusticeTests();

            case 1 -> manager.runJudgeSimulationTests();
        }

        if (!manager.getProcessorTestCases().isEmpty())
            manager.runProcessorTests();

        if (!manager.getParserTestCases().isEmpty())
            manager.runParserTests();

        Constants.printTimestamp();

    }


    // Justice creation \\

    /**
     * Creates the referee profile selected for diagnostics and direct
     * TestCaseManager referee use.
     */
    private static Justice createReferee(Collection<Order> orders) {

        if (USE_SZYKMAN_REFEREE) {
            return new SzykmanJustice(
                    orders,
                    Justice.NUM_TRIALS_DEFAULT,
                    Justice.SHUFFLE_SEED_DEFAULT);
        }

        return new Justice(
                orders,
                Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT);

    }

    /**
     * Creates the referee profile selected for diagnostics and direct
     * TestCaseManager referee use with a known random seed.
     */
    private static Justice createReferee(
            Collection<Order> orders,
            int numTrials,
            long shuffleSeed
    ) {

        if (USE_SZYKMAN_REFEREE) {
            return new SzykmanJustice(
                    orders,
                    numTrials,
                    shuffleSeed);
        }

        return new Justice(
                orders,
                numTrials,
                shuffleSeed);

    }


    // Diagnostic methods \\

    /**
     * Runs a test case repeatedly with known seeds and reports:<br><br>
     *
     * - distinct final Justice outcomes;
     * - distinct raw Judge candidates;
     * - raw-candidate occurrence frequency;
     * - seeds and trial samples that produced each candidate; and
     * - one example shuffled input order for each candidate.
     */
    public static void diagnoseJusticeStability(
            AdjudicatorTestCase testCase,
            int numSeeds,
            int numTrials
    ) {

        Set<String> finalOutcomes = new TreeSet<>();

        Map<String, CandidateStats> candidateStats = new TreeMap<>();

        for (long seed = 0; seed < numSeeds; seed++) {

            Justice justice = createReferee(
                    new ArrayList<>(Orders.deepCopy(testCase.getOrders())),
                    numTrials,
                    seed);

            justice.judge();

            finalOutcomes.add(finalOutcomeKey(justice.getOrders()));

            for (CandidateResolution candidateResolution : justice.getCandidateResolutions()) {

                String candidateKey = outcomeKey(
                        candidateResolution.getRepresentativeResolution());

                CandidateStats stats = candidateStats.computeIfAbsent(
                        candidateKey,
                        ignored -> new CandidateStats(
                                candidateResolution.getExampleInputOrder()
                        ));

                stats.record(
                        seed,
                        candidateResolution.getOccurrences(),
                        candidateResolution.getTrialNumbers());
                stats.recordCycles(
                        candidateResolution.getParadoxCycles());

            }

        }

        System.out.printf("%n[%s]%n", testCase.getName());

        System.out.printf(
                "Observed %d final outcome(s) across %d seed(s), %d trial(s) per seed.%n",
                finalOutcomes.size(),
                numSeeds,
                numTrials);

        System.out.printf(
                "Observed %d raw candidate resolution(s) before final meta-resolution.%n",
                candidateStats.size());

        int candidateNumber = 1;

        for (Map.Entry<String, CandidateStats> entry :
                candidateStats.entrySet()) {

            CandidateStats stats = entry.getValue();

            System.out.printf(
                    "%n--- RAW CANDIDATE %d ---%n",
                    candidateNumber++);

            System.out.printf(
                    "Observed %d time(s) across %d seed(s).%n",
                    stats.occurrences,
                    stats.seeds.size());

            System.out.printf(
                    "Seeds: %s%n",
                    stats.seeds);

            if (!stats.provenanceSamples.isEmpty())
                System.out.printf(
                        "Trial samples: %s%n",
                        stats.provenanceSamples);

            System.out.println("Example shuffled input order:");

            for (int i = 0; i < stats.exampleInputOrder.size(); i++) {
                System.out.printf(
                        "  [%d] %s%n",
                        i,
                        stats.exampleInputOrder.get(i));
            }

            stats.printCycles();

            System.out.printf("%n%s%n", entry.getKey());

        }

        int finalNumber = 1;

        for (String finalOutcome : finalOutcomes) {
            System.out.printf(
                    "%n--- FINAL OUTCOME %d ---%n%s%n",
                    finalNumber++,
                    finalOutcome);
        }

    }

    /**
     * Produces a compact, convoy-centered report for the DATC second-order
     * paradoxes.<br><br>
     *
     * Unlike diagnoseRefereeStability(...), this report does not print every raw
     * order as an unstructured list. It groups each candidate by:<br><br>
     *
     * - submitted convoy;
     * - corresponding convoyed army move;
     * - direct attacks on the convoying fleet; and
     * - captured recursive cycles containing that convoy.<br><br>
     *
     * This is diagnostic-only. It does not alter Justice selection logic.
     */
    public static void diagnoseSecondOrderParadox(
            AdjudicatorTestCase testCase,
            int numSeeds,
            int numTrials
    ) {

        Map<String, SecondOrderCandidateStats> candidates =
                new TreeMap<>();

        for (long seed = 0; seed < numSeeds; seed++) {

            Justice justice = createReferee(
                    new ArrayList<>(Orders.deepCopy(testCase.getOrders())),
                    numTrials,
                    seed);

            justice.judge();

            for (CandidateResolution candidateResolution : justice.getCandidateResolutions()) {

                Set<Order> resolution =
                        candidateResolution.getRepresentativeResolution();

                String key = outcomeKey(resolution);

                SecondOrderCandidateStats stats =
                        candidates.computeIfAbsent(
                                key,
                                ignored -> new SecondOrderCandidateStats(
                                        resolution,
                                        candidateResolution.getExampleInputOrder()
                                )
                        );

                stats.record(
                        seed,
                        candidateResolution.getOccurrences(),
                        candidateResolution.getTrialNumbers(),
                        candidateResolution.getParadoxCycles());

            }

        }

        System.out.printf(
                "%n============================================================%n"
                        + "[SECOND-ORDER PARADOX REPORT]%n"
                        + "%s%n"
                        + "Seeds: %d | Trials per seed: %d%n"
                        + "Distinct raw verdict-level candidates: %d%n"
                        + "============================================================%n",
                testCase.getName(),
                numSeeds,
                numTrials,
                candidates.size()
        );

        int candidateNumber = 1;

        for (SecondOrderCandidateStats stats : candidates.values()) {

            System.out.printf(
                    "%n---------------- CANDIDATE %d ----------------%n",
                    candidateNumber++);

            System.out.printf(
                    "Observed: %d time(s) across %d seed(s)%n",
                    stats.occurrences,
                    stats.seeds.size());

            System.out.printf(
                    "Seeds: %s%n",
                    stats.seeds);

            if (!stats.provenanceSamples.isEmpty())
                System.out.printf(
                        "Trial samples: %s%n",
                        stats.provenanceSamples);

            System.out.println("\nRepresentative input ordering:");

            for (int i = 0; i < stats.exampleInputOrder.size(); i++) {
                System.out.printf(
                        "  [%d] %s%n",
                        i,
                        stats.exampleInputOrder.get(i));
            }

            System.out.println("\nConvoy dependency summary:");

            List<Order> convoys = new ArrayList<>();

            for (Order order : stats.representativeResolution) {
                if (originalOrderOf(order).orderType == OrderType.CONVOY)
                    convoys.add(order);
            }

            convoys.sort(new OrderComparator());

            if (convoys.isEmpty())
                System.out.println("  No convoys in this candidate.");

            for (Order convoyVersion : convoys) {
                printConvoyDependencySummary(
                        convoyVersion,
                        stats.representativeResolution);
            }

            System.out.println("\nCaptured convoy-containing cycles:");

            List<ParadoxCycle> convoyCycles = new ArrayList<>();

            for (ParadoxCycle cycle : stats.detectedCycles.values()) {
                if (cycle.containsConvoy())
                    convoyCycles.add(cycle);
            }

            if (convoyCycles.isEmpty()) {
                System.out.println("  None.");
            } else {
                int cycleNumber = 1;
                for (ParadoxCycle cycle : convoyCycles) {
                    System.out.printf(
                            "%n  -- Cycle %d --%n",
                            cycleNumber++);
                    for (Order order : cycle.getMembers()) {
                        System.out.printf(
                                "  %s%n",
                                order);
                    }
                }
            }

            System.out.println("\nFull candidate outcome:");

            List<Order> sortedOrders = new ArrayList<>(
                    stats.representativeResolution);

            sortedOrders.sort(new OrderComparator());

            for (Order order : sortedOrders) {
                System.out.printf(
                        "  %-35s resolved=%-5b verdict=%-5b snapshot=%-5b%n",
                        order,
                        order.resolved,
                        order.verdict,
                        order.getSnapshot() != null);
            }

        }

        System.out.printf(
                "%n============================================================%n");

    }

    private static void diagnoseOrdinaryProbe(AdjudicatorTestCase testCase) {

        if (!testCase.getName().contains("6.F.28")
                && !testCase.getName().contains("6.F.29")) {
            return;
        }

        OrdinaryResolution result =
                new Jury(
                        testCase.getOrders()
                ).inquire();

        System.out.printf(
                "%n[ORDINARY RESOLUTION PROBE]%n%s%n"
                        + "Complete: %b%n"
                        + "Unresolved components: %d%n",
                testCase.getName(),
                result.isComplete(),
                result.unresolvedComponents().size()
        );

        for (Order order : testCase.getOrders()) {
            System.out.printf(
                    "  %-35s %s%n",
                    order,
                    result.stateOf(order));
        }

    }


    // Diagnostic helpers \\

    /**
     * Prints the convoy, its corresponding army movement order, and every direct
     * attack against the convoying fleet's current province.<br><br>
     *
     * A snapshot-backed HOLD is reported using its original convoy order, while
     * still showing whether this candidate transformed it under Szykman.
     */
    private static void printConvoyDependencySummary(
            Order convoyVersion,
            Collection<Order> resolution
    ) {

        Order submittedConvoy = originalOrderOf(convoyVersion);

        Order correspondingMove = null;

        for (Order order : resolution) {
            if (order.orderType != OrderType.MOVE)
                continue;
            if (order.pos0 == submittedConvoy.pos1
                    && order.pos1 == submittedConvoy.pos2) {
                correspondingMove = order;
                break;
            }
        }

        System.out.printf(
                "%n  Convoy fleet: %s%n",
                submittedConvoy);

        System.out.printf(
                "    candidate representation: %s%n",
                convoyVersion);

        System.out.printf(
                "    convoy status: resolved=%b, verdict=%b, transformedToHold=%b%n",
                convoyVersion.resolved,
                convoyVersion.verdict,
                convoyVersion.getSnapshot() != null);

        if (correspondingMove == null) {
            System.out.println(
                    "    corresponding army move: <not found>");
        } else {
            System.out.printf(
                    "    corresponding army move: %s"
                            + " | resolved=%b, verdict=%b%n",
                    correspondingMove,
                    correspondingMove.resolved,
                    correspondingMove.verdict);
        }

        List<Order> directFleetAttacks = new ArrayList<>();

        for (Order order : resolution) {
            if (order.orderType != OrderType.MOVE)
                continue;
            if (Province.equalsIgnoreCoast(
                    order.pos1,
                    submittedConvoy.pos0))
                directFleetAttacks.add(order);
        }

        directFleetAttacks.sort(new OrderComparator());

        if (directFleetAttacks.isEmpty()) {
            System.out.println(
                    "    direct attacks on convoy fleet: <none>");
            return;
        }

        System.out.println("    direct attacks on convoy fleet:");

        for (Order attack : directFleetAttacks) {
            System.out.printf(
                    "      %s | resolved=%b, verdict=%b%n",
                    attack,
                    attack.resolved,
                    attack.verdict);
        }

    }


    /**
     * Returns an order's original submitted identity.<br><br>
     *
     * A Szykman replacement HOLD preserves the original CONVOY as a snapshot.
     */
    private static Order originalOrderOf(Order order) {

        Order snapshot = order.getSnapshot();

        return snapshot == null
                ? order
                : snapshot;

    }


    // Nested diagnostic types \\

    /**
     * Aggregate diagnostic data for one raw verdict-level candidate across all
     * tested Justice seeds.
     */
    private static final class SecondOrderCandidateStats {

        private int occurrences;

        private final Set<Long> seeds;
        private final List<String> provenanceSamples;

        private final Set<Order> representativeResolution;
        private final List<Order> exampleInputOrder;

        /*
         * Keyed by ParadoxCycle.key(), because the same cycle can occur in many
         * trials of the same candidate.
         */
        private final Map<String, ParadoxCycle> detectedCycles;


        private SecondOrderCandidateStats(
                Collection<Order> representativeResolution,
                Collection<Order> exampleInputOrder
        ) {
            this.occurrences = 0;
            this.seeds = new TreeSet<>();
            this.provenanceSamples = new ArrayList<>();
            this.representativeResolution = new LinkedHashSet<>(
                    Orders.deepCopy(representativeResolution));
            this.exampleInputOrder = new ArrayList<>(
                    Orders.deepCopy(exampleInputOrder));
            this.detectedCycles = new TreeMap<>();
        }

        private void record(
                long seed,
                int occurrencesForSeed,
                Collection<Integer> trialNumbers,
                Collection<ParadoxCycle> cycles
        ) {
            this.occurrences += occurrencesForSeed;
            this.seeds.add(seed);
            for (int trial : trialNumbers) {
                if (this.provenanceSamples.size()
                        >= MAX_PROVENANCE_SAMPLES)
                    break;
                this.provenanceSamples.add(
                        "seed=" + seed + ", trial=" + trial);
            }
            for (ParadoxCycle cycle : cycles) {
                this.detectedCycles.putIfAbsent(
                        cycle.key(),
                        cycle);
            }
        }

    }


    private static String finalOutcomeKey(Collection<Order> orders) {
        return outcomeKey(orders);
    }

    private static String outcomeKey(Collection<Order> orders) {

        List<String> lines = new ArrayList<>();

        for (Order order : orders) {
            lines.add(
                    order
                            + " | resolved=" + order.resolved
                            + " | verdict=" + order.verdict
                            + " | snapshot="
                            + (order.getSnapshot() != null)
            );
        }

        Collections.sort(lines);

        return String.join("\n", lines);

    }


    private static final class CandidateStats {

        private int occurrences;
        private final Set<Long> seeds;
        private final List<String> provenanceSamples;
        private final List<Order> exampleInputOrder;
        private final Map<String, ParadoxCycle> detectedCycles;

        private CandidateStats(Collection<Order> exampleInputOrder) {
            this.occurrences = 0;
            this.seeds = new TreeSet<>();
            this.provenanceSamples = new ArrayList<>();
            this.exampleInputOrder = new ArrayList<>(
                    Orders.deepCopy(exampleInputOrder));
            this.detectedCycles = new TreeMap<>();
        }

        private void record(
                long seed,
                int occurrencesForSeed,
                Collection<Integer> trialNumbers
        ) {
            this.occurrences += occurrencesForSeed;
            this.seeds.add(seed);
            for (int trial : trialNumbers) {
                if (this.provenanceSamples.size()
                        >= MAX_PROVENANCE_SAMPLES)
                    return;
                this.provenanceSamples.add(
                        "seed=" + seed + ", trial=" + trial);
            }
        }

        private void recordCycles(Collection<ParadoxCycle> cycles) {
            for (ParadoxCycle cycle : cycles)
                this.detectedCycles.putIfAbsent(cycle.key(), cycle);
        }

        private void printCycles() {

            if (this.detectedCycles.isEmpty()) {
                System.out.println("Detected convoy/dependency cycles: none");
                return;
            }

            System.out.printf(
                    "Detected convoy/dependency cycles: %d%n",
                    this.detectedCycles.size());

            int number = 1;

            for (ParadoxCycle cycle : this.detectedCycles.values()) {
                System.out.printf(
                        "  -- CYCLE %d --%n%s%n",
                        number++,
                        cycle);
            }

        }

    }


}