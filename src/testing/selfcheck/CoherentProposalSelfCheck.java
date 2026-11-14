package testing.selfcheck;

import agents.encoding.*;
import agents.execution.ProposalInspector;
import agents.generation.*;
import agents.policy.*;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static agents.generation.MovementFeasibility.Code.*;
import static agents.generation.MovementFeasibility.Kind.*;

public final class CoherentProposalSelfCheck {

    private static int checks;
    private CoherentProposalSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Usage: CoherentProposalSelfCheck [linear-policy.bin gru-model.bin]");
        correspondence();
        routes();
        construction(args.length == 0 ? new LinearMovementPolicy(1024) : GruMovementPolicy.load(Path.of(args[1])),
                args.length == 0 ? new LinearMovementPolicy(1024) : LinearMovementPolicy.load(Path.of(args[0])));
        exhausted();
        System.out.println("CoherentProposalSelfCheck passed: " + checks + " checks");
    }

    private static void correspondence() {
        var root = root("ENGLAND ARMY Lon", "ENGLAND ARMY Bre", "ENGLAND ARMY Par",
                "ENGLAND FLEET ENG", "ENGLAND FLEET Cly", "ENGLAND FLEET Lvp", "FRANCE FLEET MAO");
        var action = action(root, Nation.ENGLAND, "ARMY Bre SUPPORT Lon Pic", "ARMY Lon MOVE Mar -",
                "ARMY Par HOLD - -", "FLEET ENG CONVOY Lon Bel", "FLEET Cly MOVE NAO -", "FLEET Lvp MOVE NAO -");
        var findings = findings(root, action);
        require(has(findings, OWN_SUPPORT_MISMATCH) && has(findings, OWN_CONVOY_MISMATCH), "Own correspondence");
        require(has(findings, NO_CONVOY_ROUTE), "Lon-Marseilles cannot use these fleet orders");
        require(findings.stream().filter(f -> f.code() == SHARED_DESTINATION).count() == 2, "Both colliding orders identified");

        var adjacent = root("GERMANY ARMY Den", "GERMANY ARMY Ber", "GERMANY ARMY Mun",
                "GERMANY ARMY Kie", "GERMANY FLEET BAL");
        require(findings(adjacent, action(adjacent, Nation.GERMANY, "ARMY Den MOVE Swe -",
                "ARMY Ber SUPPORT Mun -", "ARMY Mun SUPPORT Ber -", "ARMY Kie SUPPORT Mun -",
                "FLEET BAL SUPPORT Ber -")).isEmpty(), "Den-Swe and mutual support remain valid");
        var swap = findings(adjacent, action(adjacent, Nation.GERMANY, "ARMY Ber MOVE Mun -", "ARMY Mun MOVE Ber -"));
        require(swap.stream().filter(f -> f.code() == DIRECT_SWAP).count() == 2
                && swap.stream().noneMatch(f -> f.kind() == BLOCKER), "Direct self-bounce is a caution");
        var blocked = findings(adjacent, action(adjacent, Nation.GERMANY, "ARMY Kie MOVE Ber -"));
        require(has(blocked, OWN_STATIONARY_DESTINATION), "Stationary own destination");

        var foreign = root("ENGLAND ARMY Lon", "ENGLAND ARMY Pic", "FRANCE FLEET ENG", "FRANCE ARMY Bel");
        var dependence = findings(foreign, action(foreign, Nation.ENGLAND, "ARMY Lon MOVE Bel -", "ARMY Pic SUPPORT Lon Bel"));
        require(has(dependence, FOREIGN_CONVOY) && dependence.stream().noneMatch(f -> f.kind() == BLOCKER),
                "Available foreign convoy is explicit, not forbidden");
        require(has(findings(foreign, action(foreign, Nation.ENGLAND, "ARMY Pic SUPPORT Bel -")), FOREIGN_SUPPORT),
                "Foreign support is explicit");
        var coastalSwap = root("ENGLAND ARMY Lon", "ENGLAND ARMY Wal", "FRANCE FLEET ENG");
        require(findings(coastalSwap, action(coastalSwap, Nation.ENGLAND, "ARMY Lon MOVE Wal -", "ARMY Wal MOVE Lon -"))
                .stream().filter(f -> f.code() == FOREIGN_CONVOY).count() == 2, "Convoy-dependent swap identifies foreign cooperation");
        var coasts = root("FRANCE FLEET MAO", "FRANCE FLEET SpaNC");
        require(findings(coasts, action(coasts, Nation.FRANCE, "FLEET MAO SUPPORT Spa -")).isEmpty(),
                "Canonical support references exact-coast fleet");

        var remote = root("RUSSIA ARMY Con", "RUSSIA FLEET BAL", "RUSSIA FLEET BLA");
        var bad = action(remote, Nation.RUSSIA, "ARMY Con MOVE Bul -", "FLEET BAL CONVOY Con Bul");
        require(!MovementCoordination.inspect(remote, bad).mismatched(), "Old counter misses off-route convoy");
        require(has(findings(remote, bad), CONVOY_OFF_ROUTE), "Baltic cannot convoy Con-Bul");
    }

    private static void routes() {
        var root = root("ENGLAND ARMY Lon", "ENGLAND FLEET NTH", "ENGLAND FLEET NWG",
                "ENGLAND FLEET NAO", "ENGLAND FLEET IRI", "ENGLAND FLEET ENG", "ENGLAND FLEET MAO");
        List<Province> seas = List.of(Province.NTH, Province.NWG, Province.NAO, Province.IRI, Province.ENG, Province.MAO);
        for (Province destination : List.of(Province.Bel, Province.Por, Province.Nwy, Province.Tun)) {
            for (int mask = 0; mask < (1 << seas.size()); mask++) {
                List<String> labels = new ArrayList<>();
                labels.add("ARMY Lon MOVE " + destination + " -");
                Set<Province> available = EnumSet.noneOf(Province.class);
                for (int i = 0; i < seas.size(); i++) if ((mask & (1 << i)) != 0) {
                    available.add(seas.get(i));
                    labels.add("FLEET " + seas.get(i) + " CONVOY Lon " + destination);
                }
                var action = action(root, Nation.ENGLAND, labels.toArray(String[]::new));
                var findings = findings(root, action);
                boolean possible = path(Province.Lon, destination, available, null);
                require(has(findings, NO_CONVOY_ROUTE) == !possible, "Connected route agrees with exhaustive paths");
                for (int index = 0; index < action.orders().size(); index++) {
                    Order order = (Order) action.orders().get(index);
                    if (order.orderType() != OrderType.CONVOY) continue;
                    int slot = index;
                    boolean offRoute = findings.stream().anyMatch(f -> f.orderIndex() == slot && f.code() == CONVOY_OFF_ROUTE);
                    require(offRoute == !path(Province.Lon, destination, available, root.board().locationOf(order.unit())),
                            "Through-fleet flow agrees with exhaustive simple paths");
                }
            }
        }
        var leaf = action(root, Nation.ENGLAND, "ARMY Lon MOVE Bel -",
                "FLEET NTH CONVOY Lon Bel", "FLEET NWG CONVOY Lon Bel");
        var findings = findings(root, leaf);
        require(!has(findings, NO_CONVOY_ROUTE) && has(findings, CONVOY_OFF_ROUTE),
                "A connected dead-end fleet is not on an endpoint-to-endpoint route");
    }

    private static void construction(MovementPolicy proposal, MovementPolicy anchor) {
        List<GameState_Movement> roots = List.of(
                (GameState_Movement) StandardGameFactory.create1901().snapshot(),
                root("ENGLAND ARMY Lon", "ENGLAND ARMY Bre", "ENGLAND ARMY Par",
                        "ENGLAND FLEET ENG", "ENGLAND FLEET MAO", "ENGLAND FLEET Cly", "ENGLAND FLEET Lvp"),
                root("ENGLAND ARMY Lon", "ENGLAND ARMY Pic", "FRANCE FLEET ENG"),
                new GameState_Movement(new GameMoment(1905, GamePhase.FALL_MOVEMENT),
                        new BoardState(Map.of(), Map.of(Province.Lon, Nation.ENGLAND))));
        var generator = new CoherentPolicyActionGenerator(proposal, anchor);
        for (var root : roots) for (long seed : List.of(7L, 17L, -1L)) {
            var encoding = new MovementEncoding(root, Nation.ENGLAND);
            var request = new ActionGenerationRequest(root, Nation.ENGLAND, 8, seed);
            var result = generator.propose(request, encoding);
            require(!result.candidates().isEmpty() && result.equals(generator.propose(request, encoding)), "Deterministic nonempty fixture pool");
            require(result.attempts() <= result.target() * CoherentPolicyActionGenerator.ATTEMPTS_PER_CANDIDATE,
                    "Attempt bound");
            require(result.choices() <= (long) result.attempts() * CoherentPolicyActionGenerator.CHOICES_PER_UNIT
                    * Math.max(1, encoding.options().size()), "Choice bound");
            var prepared = anchor.prepare(encoding.input());
            Set<List<Integer>> unique = new HashSet<>();
            for (ActionCandidate candidate : result.candidates()) {
                List<Integer> action = encoding.encode(candidate.orders());
                require(unique.add(action), "Distinct complete catalog-valid action");
                require(findings(root, candidate).stream().noneMatch(f -> f.kind() == BLOCKER), "Coherent result has no blockers");
                require(candidate.logPrior() == prepared.logProbability(action), "Original full-catalog anchor score");
                require(candidate.source() == ActionCandidate.Source.POLICY, "Construction is not labeled raw sampling");
            }
            var combined = new HybridActionGenerator((r, c) -> result.candidates(),
                    new PlausibleActionGenerator(), anchor, new UniformActionGenerator()).propose(request, encoding);
            require(combined.learnedSelected() + combined.heuristicSelected() == combined.candidates().size(), "Existing hybrid accounting");
            for (var candidate : combined.candidates())
                require(findings(root, candidate).stream().noneMatch(f -> f.kind() == BLOCKER), "Plausible hybrid has no blockers");
        }
        var favorable = root("ENGLAND ARMY Lon", "ENGLAND FLEET ENG", "ENGLAND FLEET MAO");
        MovementPolicy convoy = fixed(Map.of(Province.Lon, "ARMY Lon MOVE Por -",
                Province.ENG, "FLEET ENG CONVOY Lon Por", Province.MAO, "FLEET MAO CONVOY Lon Por"));
        var result = new CoherentPolicyActionGenerator(convoy, anchor).propose(
                new ActionGenerationRequest(favorable, Nation.ENGLAND, 4, 7), new MovementEncoding(favorable, Nation.ENGLAND));
        require(result.candidates().size() == 1 && result.duplicates() > 0, "Multi-fleet convoy retained; duplicate budget reported");
        var coastal = root("ENGLAND FLEET SpaNC", "ENGLAND FLEET MAO");
        var full = new MovementEncoding(coastal, Nation.ENGLAND);
        var scope = new ProvinceScope(List.of(Province.Spa, Province.MAO));
        var restricted = scope.restrict(coastal, full.catalog());
        var masks = full.input().slots().stream().map(slot -> slot.options().stream().map(MovementInput.Option::label).toList()).toList();
        MovementPolicy checked = input -> {
            require(input.slots().stream().map(slot -> slot.options().stream().map(MovementInput.Option::label).toList()).toList().equals(masks),
                    "Catalog restrictions do not change model input masks");
            return proposal.prepare(input);
        };
        var scoped = new CoherentPolicyActionGenerator(checked, anchor).propose(
                new ActionGenerationRequest(coastal, Nation.ENGLAND, 8, 7), restricted);
        require(!scoped.candidates().isEmpty(), "Restricted fixture produces candidates");
        for (var candidate : scoped.candidates()) {
            scope.requireWithin(coastal, candidate);
            require(candidate.logPrior() == anchor.prepare(full.input()).logProbability(full.encode(candidate.orders())),
                    "Scoped candidates retain full-catalog anchor score");
        }
    }

    private static void exhausted() {
        var root = root("ENGLAND ARMY Lon", "ENGLAND FLEET NTH", "ENGLAND FLEET NWG");
        MovementPolicy impossible = fixed(Map.of(Province.Lon, "ARMY Lon MOVE Bel -",
                Province.NTH, "FLEET NTH CONVOY Lon Bel", Province.NWG, "FLEET NWG CONVOY Lon Bel"));
        MovementPolicy anchor = new LinearMovementPolicy(1024);
        var request = new ActionGenerationRequest(root, Nation.ENGLAND, 4, 7);
        var encoding = new MovementEncoding(root, Nation.ENGLAND);
        var generator = new CoherentPolicyActionGenerator(impossible, anchor);
        var result = generator.propose(request, encoding);
        require(result.candidates().isEmpty() && result.deadEnds() == result.attempts(), "No success-shaped fallback");
        var inspector = new ProposalInspector(anchor, anchor, impossible);
        var options = new ProposalInspector.Options(4, 2, 1, 2, 7, 7, 7, false);
        boolean failed = false;
        try { inspector.inspect(root, Nation.ENGLAND, ProposalInspector.Method.COHERENT_GRU, options); }
        catch (IllegalStateException expected) { failed = true; }
        require(failed, "Pure coherent inspection reports exhaustion");
        var hybrid = inspector.inspect(root, Nation.ENGLAND, ProposalInspector.Method.COHERENT_GRU_HYBRID, options);
        var stats = hybrid.generation().get(Nation.ENGLAND);
        require(stats.learned().candidates().isEmpty() && stats.hybrid().orElseThrow().learnedSelected() == 0
                && stats.hybrid().orElseThrow().heuristicSelected() == hybrid.proposals().size(), "Plausible fills hybrid openly");
        require(hybrid.proposals().stream().allMatch(p -> p.findings().stream().noneMatch(f -> f.kind() == BLOCKER)),
                "Heuristic coverage is coherent");
    }

    private static MovementPolicy fixed(Map<Province, String> selected) {
        return input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                var slot = input.slots().get(prefix.size());
                String label = selected.get(slot.location());
                double[] result = new double[slot.options().size()];
                Arrays.fill(result, Double.NEGATIVE_INFINITY);
                boolean found = false;
                for (int i = 0; i < result.length; i++) if (slot.options().get(i).label().equals(label)) {
                    result[i] = 0; found = true;
                }
                if (!found) throw new AssertionError("Fixture label not in mask: " + label);
                return result;
            }
        };
    }

    private static GameState_Movement root(String... units) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (String text : units) {
            String[] parts = text.split(" ");
            locations.put(new UnitId(UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)),
                    Nation.valueOf(parts[0]), UnitType.valueOf(parts[1]), Province.valueOf(parts[2])), Province.valueOf(parts[2]));
        }
        return new GameState_Movement(new GameMoment(1905, GamePhase.SPRING_MOVEMENT), new BoardState(locations, Map.of()));
    }
    private static ActionCandidate action(GameState_Movement root, Nation power, String... labels) {
        var encoding = new MovementEncoding(root, power);
        Map<Province, String> selected = new EnumMap<>(Province.class);
        for (String label : labels) selected.put(Province.valueOf(label.split(" ")[1]), label);
        List<Order> orders = new ArrayList<>();
        for (var slot : encoding.options()) {
            Order first = (Order) slot.getFirst();
            String label = selected.get(root.board().locationOf(first.unit()));
            orders.add(label == null ? Order.hold(first.unit()) : (Order) slot.stream()
                    .filter(order -> encoding.token(order).equals(label)).findFirst().orElseThrow());
        }
        return new ActionCandidate(power, orders, 0, ActionCandidate.Source.POLICY);
    }
    private static List<MovementFeasibility.Finding> findings(GameState_Movement root, ActionCandidate candidate) {
        return new MovementFeasibility(root, new MovementEncoding(root, candidate.power()).catalog()).inspect(candidate);
    }
    private static boolean has(List<MovementFeasibility.Finding> findings, MovementFeasibility.Code code) {
        return findings.stream().anyMatch(f -> f.code() == code);
    }
    private static boolean path(Province origin, Province destination, Set<Province> available, Province required) {
        for (Province start : available)
            if (start.isAdjacentTo(origin) && visit(start, destination, available, required, EnumSet.noneOf(Province.class)))
                return true;
        return false;
    }
    private static boolean visit(Province at, Province destination, Set<Province> available, Province required, Set<Province> used) {
        if (!used.add(at)) return false;
        if (at.isAdjacentTo(destination) && (required == null || used.contains(required))) return true;
        for (Province next : available)
            if (!used.contains(next) && at.isAdjacentTo(next) && visit(next, destination, available, required, used)) return true;
        used.remove(at);
        return false;
    }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}