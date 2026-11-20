package _app;

import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import domain.Nation;
import game.Game;
import io.training.*;
import misc.scoring.ScoringSystem;
import performance.*;
import phase.movement.MovementProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static io.training.SelfPlayFiles.object;

/**
 * Single-threaded profiling of one frozen decision from a completed archive.
 * No game collection, training, cache activation or strength evaluation.
 */
public final class AgentPerformanceApp {


    private AgentPerformanceApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 9)
            throw new IllegalArgumentException(
                    "Usage: AgentPerformanceApp <completed-archive> <phase-index> <power> "
                            + "<value-model.bin|baseline> <anchor.bin|none> <gru-model.bin|none> "
                            + "<warmups> <repeats> <new-output-directory>");
        int phaseIndex = Integer.parseInt(args[1]);
        Nation power = Nation.valueOf(args[2]);
        int warmups = Integer.parseInt(args[6]);
        int repeats = Integer.parseInt(args[7]);
        if (phaseIndex < 0 || warmups < 0 || repeats < 1)
            throw new IllegalArgumentException("Phase/warmups must be nonnegative and repeats positive");
        Path archive = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[8]).toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(output.toString());
        output = output.getParent().toRealPath().resolve(output.getFileName());
        if (output.startsWith(archive))
            throw new IllegalArgumentException("Profiling output must be outside the input archive");

        PerformanceStats preparation = new PerformanceStats(true);
        SelfPlayDataset.GameExample example;
        try (var reading = preparation.measure("archive read and replay")) {
            example = SelfPlayDataset.open(archive);
        }
        if (phaseIndex >= example.phases().size())
            throw new IllegalArgumentException("Phase index is beyond this archive");
        var phase = example.phases().get(phaseIndex);
        var recorded = phase.decisions().stream().filter(value -> value.power() == power)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Power has no decision in this phase"));
        AgentSettings settings;
        try (var loading = preparation.measure("model loading")) {
            settings = load(example.configuration().seats().get(power), args[3], args[4], args[5]);
        }
        var configuration = example.configuration();
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            seats.put(nation, settings);
        var options = new GameRunner.Options(configuration.lastYear(), configuration.seed(),
                ScoringSystem.SUM_OF_SQUARES, MovementProcessor.Policy.JUDGE,
                configuration.adjudicationTrials(), configuration.adjudicationSeed(), seats);

        AgentDecision reference;
        try (var checking = preparation.measure("unprofiled reference decision")) {
            reference = decide(phase, recorded, settings, options, AgentPerformance.disabled());
        }
        boolean reproduced = reference.pools().equals(recorded.pools())
                && reference.distribution().equals(recorded.policy())
                && reference.searchResult().equals(recorded.search())
                && reference.selectedIndex() == recorded.selectedIndex();
        System.out.println("Fixed decision: " + phase.state().moment() + " " + power);
        System.out.println("Recorded decision reproduced: " + reproduced);
        if (!reproduced)
            System.out.println("WARNING: Current execution differs from the archived decision. "
                    + "Do not interpret timing differences as a same-work speedup.");

        AgentPerformance warming = new AgentPerformance(true);
        for (int repeat = 0; repeat < warmups; repeat++)
            AgentPerformance.requireSameDecision(reference, decide(phase, recorded, settings, options, warming));
        AgentPerformance measured = new AgentPerformance(true);
        for (int repeat = 0; repeat < repeats; repeat++)
            AgentPerformance.requireSameDecision(reference, decide(phase, recorded, settings, options, measured));

        Map<String, Object> decision = SelfPlayRecorder.decision(reference);
        decision.remove("elapsed_nanos");
        String decisionHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SelfPlayFiles.json(decision).getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> context = object(
                "purpose", "FIXED_DECISION_PERFORMANCE_ONLY",
                "archive_fingerprint", example.fingerprint(), "logical_game_key", example.logicalKey(),
                "game_id", example.id().toString(), "phase_index", phaseIndex,
                "year", phase.state().moment().year(), "phase", phase.state().moment().gamePhase().name(),
                "power", power.name(), "settings", SelfPlayRecorder.settings(settings),
                "last_year", options.lastYear(), "ruleset_id", options.rulesetId(),
                "search_seed", Long.toString(recorded.searchSeed()),
                "rollout_seed", Long.toString(recorded.rolloutSeed()),
                "selection_seed", Long.toString(recorded.selectionSeed()),
                "verification_decisions", 1, "warmups", warmups, "measured_repeats", repeats,
                "recorded_decision_reproduced", reproduced,
                "profiled_decisions_equal_reference", true, "decision_sha256", decisionHash,
                "joint_cache_enabled", false,
                "environment", object(
                        "java_version", System.getProperty("java.version"),
                        "java_vendor", System.getProperty("java.vendor"),
                        "vm_name", System.getProperty("java.vm.name"),
                        "vm_version", System.getProperty("java.vm.version"),
                        "os_name", System.getProperty("os.name"),
                        "os_version", System.getProperty("os.version"),
                        "os_arch", System.getProperty("os.arch"),
                        "available_processors", Runtime.getRuntime().availableProcessors(),
                        "max_heap_bytes", Runtime.getRuntime().maxMemory()));
        PerformanceReport.write(output, context, List.of(
                new PerformanceReport.Section("preparation", preparation.snapshot(), Map.of()),
                warming.snapshot("warmup"), measured.snapshot("measured")));
        System.out.println("\nPreparation (not included in measured decisions):");
        preparation.printTo(System.out);
        System.out.println("\nMeasured decisions only:");
        measured.printTo(System.out);
        System.out.println("Completed performance report: " + output);

    }

    private static AgentSettings load(
            SelfPlayDataset.Seat seat, String valuePath, String anchorPath, String gruPath
    ) throws Exception {

        Optional<AgentModels> movement;
        if (anchorPath.equals("none")) {
            if (!gruPath.equals("none"))
                throw new IllegalArgumentException("A GRU requires its anchor");
            movement = Optional.empty();
        } else {
            movement = Optional.of(AgentModels.load(Path.of(anchorPath),
                    gruPath.equals("none") ? Optional.empty() : Optional.of(Path.of(gruPath))));
        }
        if (!movement.map(AgentModels::identities).orElse(Map.of()).equals(seat.movementModels()))
            throw new IllegalArgumentException("Movement artifacts differ from the recorded seat");
        Optional<NeuralValueModel> value = valuePath.equals("baseline")
                ? Optional.empty() : Optional.of(NeuralValueModel.load(Path.of(valuePath)));
        if (!value.map(model -> new SelfPlayDataset.ValueModel(
                model.sha256(), model.checkpointSha256(), model.datasetFingerprint())).equals(seat.valueModel()))
            throw new IllegalArgumentException("Value artifact differs from the recorded seat");
        return new AgentSettings(seat.method(), seat.candidates(), seat.iterations(),
                seat.rollouts(), seat.rolloutLimit(), seat.output(), seat.root(), seat.continuation(),
                movement, value);

    }

    private static AgentDecision decide(
            SelfPlayDataset.Phase phase, SelfPlayDataset.Decision recorded,
            AgentSettings settings, GameRunner.Options options, AgentPerformance performance
    ) {

        Game game = options.createGame(phase.state(), performance.movement());
        AgentDecision decision = settings.decide(game, recorded.power(), options,
                recorded.searchSeed(), recorded.rolloutSeed(), recorded.selectionSeed(), performance);
        if (!game.snapshot().equals(phase.state()) || game.getSnapshot() != null
                || !game.processorHistory().isEmpty())
            throw new IllegalStateException("Profiled decision changed its simulation root");
        return decision;

    }

}