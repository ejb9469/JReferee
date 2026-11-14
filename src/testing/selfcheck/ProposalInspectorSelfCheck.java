package testing.selfcheck;

import agents.encoding.*;
import agents.execution.ProposalInspector;
import agents.execution.ProposalInspector.*;
import agents.evaluation.*;
import agents.generation.*;
import agents.policy.*;
import agents.search.*;
import domain.*;
import game.*;
import io.json.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;
import ui.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProposalInspectorSelfCheck {

    private static int checks;
    private static final Options SMALL = new Options(4, 2, 1, 2, 7, 11, 17, false);
    private static final JsonAccess JSON = new JsonAccess((path, message) -> new IllegalArgumentException(path + ": " + message));
    private ProposalInspectorSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Usage: ProposalInspectorSelfCheck [linear-policy.bin gru-model.bin]");
        MovementPolicy linear = new LinearMovementPolicy(1024), gru = weighted();
        ProposalInspector inspector = new ProposalInspector(linear, linear, gru);
        if (args.length == 2) {
            linear = LinearMovementPolicy.load(Path.of(args[0]));
            gru = GruMovementPolicy.load(Path.of(args[1]));
            inspector = ProposalInspector.load(Path.of(args[0]), Path.of(args[0]), Path.of(args[1]));
            require(inspector.models().size() == 5, "Loaded identities");
        }
        GameState_Movement opening = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        for (Method method : Method.values()) {
            Inspection result = inspector.inspect(opening, Nation.RUSSIA, method, SMALL);
            verify(result, linear, gru);
            require(result.equals(inspector.inspect(opening, Nation.RUSSIA, method, SMALL)), "Repeatable " + method);
            expect(IllegalStateException.class, () -> result.top(1, Ranking.SEARCH));
            expect(UnsupportedOperationException.class, () -> result.proposals().clear());
            expect(UnsupportedOperationException.class, () -> result.pools().get(Nation.RUSSIA).clear());
            expect(IllegalArgumentException.class, () -> result.top(0, Ranking.GRU_POLICY));
            expect(IllegalArgumentException.class, () -> result.top(5, Ranking.GRU_POLICY));
        }
        Options searched = new Options(4, 2, 1, 2, 7, 11, 17, true);
        Inspection solved = inspector.inspect(opening, Nation.RUSSIA, Method.GRU_HYBRID, searched);
        verify(solved, linear, gru);
        require(solved.pools().keySet().equals(opening.aliveNations()), "All living search pools");
        require(solved.proposals().stream().map(Proposal::action).toList().equals(
                inspector.inspect(opening, Nation.RUSSIA, Method.GRU_HYBRID, SMALL).pools().get(Nation.RUSSIA)), "Search retains own pool");
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                new UniformActionGenerator(), ProposalInspectorSelfCheck::game, 1, 2, 17);
        Game simulation = game(opening);
        var trials = JointActionEvaluator.uncached(simulation, evaluator);
        var direct = PaperSearchProfile.forState(opening).solver(evaluator, new UniformActionGenerator(), 4, 2, 11,
                PolicyOutput.AVERAGE).solve(simulation, solved.pools(), trials);
        require(direct.equals(solved.search().orElseThrow()) && trials.counts().equals(solved.work().orElseThrow()), "Existing solver parity");
        require(simulation.snapshot().equals(opening) && simulation.getSnapshot() == null, "Unchanged search root");
        require(solved.equals(inspector.inspect(opening, Nation.RUSSIA, Method.GRU_HYBRID, searched)), "Repeatable search");

        Map<UnitId, Province> units = new LinkedHashMap<>();
        units.put(new UnitId(UUID.nameUUIDFromBytes(new byte[]{1}), Nation.RUSSIA, UnitType.FLEET, Province.StpSC), Province.StpSC);
        units.put(new UnitId(UUID.nameUUIDFromBytes(new byte[]{2}), Nation.FRANCE, UnitType.FLEET, Province.SpaNC), Province.SpaNC);
        var sparse = new GameState_Movement(new GameMoment(1905, GamePhase.FALL_MOVEMENT),
                new BoardState(units, Map.of(Province.Con, Nation.TURKEY)));
        for (Nation power : sparse.aliveNations()) {
            Inspection result = inspector.inspect(sparse, power, Method.GRU, SMALL);
            verify(result, linear, gru);
            if (power == Nation.TURKEY) {
                require(result.proposals().size() == 1 && result.proposals().getFirst().orders().isEmpty(), "Center-only empty action");
                require(result.top(4, Ranking.GRU_POLICY).size() == 1 && result.proposals().getFirst().logGru() == 0, "Short pool and empty probability");
            } else require(result.proposals().stream().allMatch(p -> p.orders().getFirst().contains(
                    power == Nation.RUSSIA ? "StpSC" : "SpaNC")), "Exact coast origin");
        }
        var emptySearch = inspector.inspect(sparse, Nation.TURKEY, Method.GRU, searched);
        require(emptySearch.proposals().getFirst().search().orElseThrow().probability() == 1, "Center-only search mass");
        ProposalInspector chosen = inspector;
        expect(IllegalArgumentException.class, () -> chosen.inspect(sparse, Nation.ITALY, Method.GRU, SMALL));
        expect(IllegalArgumentException.class, () -> new Options(65, 1, 1, 1, 0, 0, 0, false));
        expect(IllegalArgumentException.class, () -> new Options(1, 0, 1, 1, 0, 0, 0, false));

        AtomicInteger preparations = new AtomicInteger();
        MovementPolicy counted = input -> { preparations.incrementAndGet(); return new LinearMovementPolicy(1024).prepare(input); };
        var cached = new ProposalInspector(counted, counted, counted).inspect(opening, Nation.FRANCE, Method.GRU_HYBRID, SMALL);
        require(preparations.get() == 1, "Reuse equivalent prepared inputs within a request");
        require(cached.top(4, Ranking.LINEAR_POLICY).equals(cached.proposals()), "Stable generation-order ties");
        MovementPolicy broken = input -> { throw new IllegalStateException("Deliberate inference failure"); };
        expect(IllegalStateException.class, () -> new ProposalInspector(counted, counted, broken).inspect(opening, Nation.FRANCE, Method.GRU, SMALL));
        http(opening);
        System.out.println("ProposalInspectorSelfCheck passed: " + checks + " checks");
    }

    private static void verify(Inspection result, MovementPolicy linear, MovementPolicy gru) {
        var encoding = new MovementEncoding(result.root(), result.power());
        var input = encoding.input();
        var first = linear.prepare(input);
        var second = gru.prepare(input);
        double anchorMass = 0, searchMass = 0;
        Set<List<Integer>> seen = new HashSet<>();
        for (Proposal proposal : result.proposals()) {
            var action = encoding.encode(proposal.action().orders());
            require(seen.add(action), "Distinct legal complete action");
            require(proposal.orders().equals(proposal.action().orders().stream().map(encoding::token).toList()), "Canonical tokens");
            require(Math.abs(first.logProbability(action) - proposal.logLinear()) < 1e-12, "Exact linear score");
            require(Math.abs(second.logProbability(action) - proposal.logGru()) < 1e-12, "Exact GRU score");
            require(proposal.coordination().equals(MovementCoordination.inspect(result.root(), proposal.action())), "Coordination counts");
            anchorMass += proposal.anchorPoolProbability();
            if (proposal.search().isPresent()) searchMass += proposal.search().orElseThrow().probability();
        }
        require(Math.abs(anchorMass - 1) < 1e-10 && (!result.options().search() || Math.abs(searchMass - 1) < 1e-10), "Whole-pool normalization");
        for (Ranking ranking : Ranking.values()) {
            if (ranking == Ranking.SEARCH && !result.options().search()) continue;
            var sorted = result.top(result.options().candidates(), ranking);
            for (int index = 1; index < sorted.size(); index++)
                require(sorted.get(index - 1).score(ranking) >= sorted.get(index).score(ranking), "Descending ranking");
            require(result.top(1, ranking).getFirst().equals(sorted.getFirst()), "Subset preserves probability");
        }
        Map<String, Object> json = JSON.object(JsonReader.read(ProposalInspectionJsonWriter.toJson(result)), "$");
        require(JSON.array(json.get("proposals"), "proposals").size() == result.proposals().size(), "Complete JSON pool");
        require((json.get("searchSettings") != null) == result.options().search(), "Explicit absent search");
    }

    private static void http(GameState_Movement root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        MovementPolicy slow = input -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return new LinearMovementPolicy(1024).prepare(input);
        };
        var inspector = new ProposalInspector(slow, slow, slow);
        try (var client = HttpClient.newHttpClient();
             var server = new ProposalViewerServer(inspector, List.of(new ProposalViewerServer.Position("opening", "<test>", null, root)),
                     0, BoardViewerServer.DEFAULT_STATIC_ROOT)) {
            server.start();
            String base = "http://127.0.0.1:" + server.address().getPort();
            require(get(client, base + "/").statusCode() == 200, "Viewer HTML");
            require(get(client, base + "/ui/movement-map.js").statusCode() == 200, "Shared renderer");
            require(get(client, base + "/ui/../ProposalViewerServer.java").statusCode() == 404, "Asset allowlist");
            require(get(client, base + "/api/inspect").statusCode() == 405, "POST required");
            String request = """
                    {"position":"opening","power":"FRANCE","method":"GRU","candidates":2,"iterations":1,
                     "rollouts":1,"rolloutCandidates":2,"poolSeed":"-9223372036854775808",
                     "searchSeed":"9223372036854775807","rolloutSeed":"7","search":false}
                    """;
            require(client.send(post(base, "{"), HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "Malformed JSON");
            require(client.send(post(base, request.replace("\"candidates\":2", "\"candidates\":65")), HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "Budget limit");
            require(client.send(post(base, request.replace("\"FRANCE\"", "\"INVALID\"")), HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "Invalid nation");
            require(client.send(post(base, " ".repeat(4097)), HttpResponse.BodyHandlers.ofString()).statusCode() == 413, "Request size limit");
            var pending = client.sendAsync(post(base, request), HttpResponse.BodyHandlers.ofString());
            try {
                require(entered.await(5, TimeUnit.SECONDS), "Inference started");
                require(get(client, base + "/api/positions").statusCode() == 200, "Responsive during inference");
                require(client.send(post(base, request), HttpResponse.BodyHandlers.ofString()).statusCode() == 429, "Concurrent work rejected");
            } finally { release.countDown(); }
            var response = pending.get(10, TimeUnit.SECONDS);
            require(response.statusCode() == 200, "Inference HTTP success");
            for (int index = 0; index < 40; index++)
                require(client.send(post(base, request), HttpResponse.BodyHandlers.ofString()).statusCode() == 200,
                        "Sequential request can start after the preceding response");
            var data = JSON.object(JsonReader.read(response.body()), "$");
            require(JSON.object(data.get("position"), "position").get("communication") == null, "Unknown label stays null");
            var inspection = JSON.object(data.get("inspection"), "inspection");
            require(JSON.object(inspection.get("options"), "options").get("poolSeed").equals("-9223372036854775808"), "Exact 64-bit seed JSON");
            var forbidden = HttpRequest.newBuilder(URI.create(base + "/api/positions")).header("Origin", "https://example.invalid").GET().build();
            require(client.send(forbidden, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "Foreign origin rejected");
        } finally { release.countDown(); }
    }

    private static HttpRequest post(String base, String body) {
        return HttpRequest.newBuilder(URI.create(base + "/api/inspect")).header("Content-Type", "application/json")
                .header("X-JReferee-Inspect", "1").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static MovementPolicy weighted() {
        return input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                input.requirePrefix(prefix);
                int count = input.slots().get(prefix.size()).options().size();
                double[] values = new double[count];
                double sum = (double) count * (count + 1) / 2;
                for (int index = 0; index < count; index++) values[index] = Math.log((index + 1) / sum);
                return values;
            }
        };
    }
    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Expected " + type.getSimpleName(), failure);
            checks++; return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}