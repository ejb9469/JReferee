package testing.selfcheck;

import agents.encoding.*;
import agents.generation.*;
import agents.policy.*;
import domain.*;
import game.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Python/Java conditional probabilities, model loading and live policy contracts.
 * The supplied export contains synthetic and optional validation examples.
 */
public final class GruPolicySelfCheck {

    private static final double TOLERANCE = 2e-4;
    private static int checks, conditionals, values;
    private static double maximumError;

    private GruPolicySelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: GruPolicySelfCheck [export-directory]");
        Path directory = Path.of(args.length == 0 ? "data/movement-gru-java-v1" : args[0]);
        Path modelPath = directory.resolve("model.bin");
        GruMovementPolicy policy = GruMovementPolicy.load(modelPath);
        List<MovementInput> inputs = parity(policy, directory.resolve("parity.bin"));
        failures(modelPath);
        live(policy);
        benchmark(policy, inputs);
        System.out.printf(Locale.ROOT, "Model SHA-256=%s%nCheckpoint SHA-256=%s%n", policy.sha256(), policy.checkpointSha256());
        System.out.printf(Locale.ROOT, "Parity: cases=%d conditionals=%d values=%d max_logp_error=%.9g tolerance=%.1g%n",
                inputs.size(), conditionals, values, maximumError, TOLERANCE);
        System.out.println("GruPolicySelfCheck passed: " + checks + " checks");
    }

    private static List<MovementInput> parity(GruMovementPolicy policy, Path path) throws Exception {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("Parity file exceeds 64 MiB");
        byte[] file = Files.readAllBytes(path);
        require(file.length >= 108 && Arrays.equals(hash(Arrays.copyOf(file, file.length - 32)),
                Arrays.copyOfRange(file, file.length - 32, file.length)), "Parity checksum");
        List<MovementInput> inputs = new ArrayList<>();
        try (var in = new DataInputStream(new ByteArrayInputStream(file, 0, file.length - 32))) {
            require(Arrays.equals(in.readNBytes(8), "JREFCHK1".getBytes(StandardCharsets.US_ASCII)), "Parity format");
            require(HexFormat.of().formatHex(in.readNBytes(32)).equals(NeuralMovementSpec.sha256()), "Parity specification");
            require(HexFormat.of().formatHex(in.readNBytes(32)).equals(policy.sha256()), "Parity model identity");
            int cases = count(in, 1, 266);
            for (int c = 0; c < cases; c++) {
                MovementInput input = input(in);
                inputs.add(input);
                MovementPolicy.Prepared prepared = policy.prepare(input);
                int paths = count(in, 1, 4);
                for (int p = 0; p < paths; p++) {
                    List<Integer> action = new ArrayList<>();
                    for (var slot : input.slots()) action.add(count(in, 0, slot.options().size() - 1));
                    double total = 0;
                    List<double[]> previous = new ArrayList<>();
                    boolean greedy = true, separated = true;
                    for (int slot = 0; slot < action.size(); slot++) {
                        double[] actual = prepared.logProbabilities(action.subList(0, slot));
                        MovementPolicy.requireDistribution(actual, input.slots().get(slot).options().size());
                        double[] expected = new double[actual.length];
                        for (int index = 0; index < actual.length; index++) {
                            expected[index] = in.readFloat();
                            close(actual[index], expected[index], TOLERANCE, "Conditional " + c + "/" + p + "/" + slot + "/" + index);
                            maximumError = Math.max(maximumError, Math.abs(actual[index] - expected[index]));
                            values++;
                        }
                        int best = 0;
                        for (int index = 1; index < expected.length; index++)
                            if (expected[index] > expected[best]) best = index;
                        greedy &= best == action.get(slot);
                        for (int index = 0; index < expected.length; index++)
                            if (index != best && expected[best] - expected[index] <= 2 * TOLERANCE) separated = false;
                        total += expected[action.get(slot)];
                        previous.add(actual.clone());
                        actual[0] = 123;
                        conditionals++;
                    }
                    close(prepared.logProbability(action), total, TOLERANCE * Math.max(1, action.size()), "Joint log probability");
                    if (greedy && separated) require(prepared.greedy().equals(action), "Greedy action with separated maxima");
                    for (int slot = action.size() - 1; slot >= 0; slot--)
                        require(Arrays.equals(previous.get(slot), prepared.logProbabilities(action.subList(0, slot))),
                                "Independent returned arrays and out-of-order prefix calls");
                    expect(IllegalArgumentException.class, () -> prepared.logProbabilities(action));
                }
                var sample = prepared.sample(new Random(7));
                require(sample.equals(prepared.sample(new Random(7))), "Seeded Java sampling");
                input.requireAction(sample);
                require(Double.isFinite(prepared.logProbability(sample)), "Finite sampled joint score");
                if (!sample.isEmpty()) {
                    expect(IllegalArgumentException.class, () -> prepared.logProbabilities(List.of(-1)));
                    expect(IllegalArgumentException.class, () -> prepared.logProbability(List.of()));
                }
            }
            require(in.read() == -1, "No trailing parity records");
        }
        return inputs;
    }

    private static MovementInput input(DataInputStream in) throws IOException {
        byte[] board = in.readNBytes(300);
        if (board.length != 300) throw new EOFException();
        int power = count(in, 1, 7), phase = count(in, 1, 2), year = count(in, 1, Integer.MAX_VALUE);
        int slots = count(in, 0, 75), options = 0;
        byte[] locations = new byte[slots];
        int[] offsets = new int[slots + 1];
        var fields = new ByteArrayOutputStream();
        List<MovementInput.Unit> units = new ArrayList<>();
        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        for (int row = 0; row < 75; row++) {
            int type = Byte.toUnsignedInt(board[row * 4]), owner = Byte.toUnsignedInt(board[row * 4 + 1]);
            int location = Byte.toUnsignedInt(board[row * 4 + 2]), center = Byte.toUnsignedInt(board[row * 4 + 3]);
            require(type <= 2 && owner <= 7 && location < 82 && center <= 7, "Board vocabulary");
            if (type != 0)
                units.add(new MovementInput.Unit(nation(owner), UnitType.valueOf(NeuralMovementSpec.UNIT_TYPES.get(type)),
                        NeuralMovementSpec.location(location)));
            if (center != 0) centers.put(NeuralMovementSpec.boardLocation(row), nation(center));
        }
        units.sort(Comparator.comparing(unit -> unit.location().name()));
        List<MovementInput.Slot> masks = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            locations[slot] = in.readByte();
            Province location = NeuralMovementSpec.location(Byte.toUnsignedInt(locations[slot]));
            int count = count(in, 1, 100000);
            options += count;
            offsets[slot + 1] = options;
            require(options <= 100000, "Bounded parity example");
            List<MovementInput.Option> choices = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int unit = in.readUnsignedByte(), origin = in.readUnsignedByte(), kind = in.readUnsignedByte();
                int target = in.readUnsignedByte(), auxiliary = in.readUnsignedByte();
                require(unit >= 1 && unit <= 2 && origin >= 1 && origin < 82 && kind >= 1 && kind <= 4
                        && target < 82 && auxiliary < 82, "Order vocabulary");
                fields.writeBytes(new byte[]{(byte) unit, (byte) origin, (byte) kind, (byte) target, (byte) auxiliary});
                choices.add(MovementInput.Option.parse(NeuralMovementSpec.UNIT_TYPES.get(unit) + " "
                        + name(origin) + " " + NeuralMovementSpec.ORDER_TYPES.get(kind) + " " + name(target) + " " + name(auxiliary)));
            }
            masks.add(new MovementInput.Slot(location, choices));
        }
        var input = new MovementInput(new GameMoment(year, GamePhase.valueOf(NeuralMovementSpec.PHASES.get(phase))),
                nation(power), units, centers, masks);
        var encoded = new NeuralMovementEncoding(input);
        require(encoded.board().equals(ByteBuffer.wrap(board)) && encoded.units() == slots
                        && encoded.optionCount() == options && encoded.power() == power && encoded.phase() == phase
                        && encoded.year() == year && encoded.slotLocations().equals(ByteBuffer.wrap(locations))
                        && encoded.optionOffsets().equals(IntBuffer.wrap(offsets))
                        && encoded.orderFields().equals(ByteBuffer.wrap(fields.toByteArray())),
                "Numeric fixture uses the live Java encoder");
        return input;
    }

    private static void live(GruMovementPolicy policy) {
        var root = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        MovementPolicy anchor = new LinearMovementPolicy(1024);
        for (Nation power : root.aliveNations()) {
            var encoding = new MovementEncoding(root, power);
            var prepared = policy.prepare(encoding.input());
            var action = prepared.sample(new Random(19));
            double score = prepared.logProbability(action);
            var request = new ActionGenerationRequest(root, power, 4, 17);
            var proposals = new PolicyActionGenerator(policy, anchor).propose(request, encoding);
            for (var proposal : proposals.proposals()) {
                var indices = encoding.encode(proposal.candidate().orders());
                close(proposal.logProposal(), prepared.logProbability(indices), 1e-12, "Generator proposal probability");
                close(proposal.candidate().logPrior(), anchor.prepare(encoding.input()).logProbability(indices), 1e-12, "Independent anchor");
                require(new CatalogActionValidator().validate(root, proposal.candidate(), encoding.catalog()).isEmpty(), "Legal generated action");
            }
            var full = encoding.input();
            var restricted = new MovementInput(full.moment(), full.power(), full.units(), full.centers(),
                    full.slots().stream().map(slot -> new MovementInput.Slot(slot.location(),
                            slot.options().stream().filter(option -> option.type() == OrderType.HOLD).toList())).toList());
            var holds = policy.prepare(restricted);
            close(holds.logProbability(Collections.nCopies(restricted.slots().size(), 0)), 0, 0, "Restricted mask normalized");
            close(prepared.logProbability(action), score, 0, "Another preparation cannot change prior state");
        }
    }

    private static void failures(Path model) throws Exception {
        byte[] original = Files.readAllBytes(model);
        Path file = Files.createTempFile("jreferee-gru-corrupt-", ".bin");
        try {
            byte[] corrupt = original.clone();
            corrupt[0] ^= 1;
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            reseal(corrupt);
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            Files.write(file, Arrays.copyOf(original, original.length - 1));
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            Files.write(file, Arrays.copyOf(original, original.length + 1));
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            ByteBuffer header = ByteBuffer.wrap(original);
            header.position(8);
            for (int i = 0; i < 3; i++) header.position(header.position() + 4 + header.getInt(header.position()));
            int specHash = header.position();
            corrupt = original.clone();
            corrupt[specHash] ^= 1;
            reseal(corrupt);
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            int hiddenOffset = specHash + 128 + 4;
            corrupt = original.clone();
            ByteBuffer.wrap(corrupt).putInt(hiddenOffset, Integer.MAX_VALUE);
            reseal(corrupt);
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            int firstName = hiddenOffset + 8;
            int firstRank = firstName + 4 + ByteBuffer.wrap(original).getInt(firstName);
            corrupt = original.clone();
            ByteBuffer.wrap(corrupt).putInt(firstRank + 4, 83);
            reseal(corrupt);
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
            corrupt = original.clone();
            ByteBuffer.wrap(corrupt).putFloat(corrupt.length - 36, Float.NaN);
            reseal(corrupt);
            Files.write(file, corrupt);
            expect(IOException.class, () -> GruMovementPolicy.load(file));
        } finally {
            Files.delete(file);
        }
    }

    private static void benchmark(GruMovementPolicy policy, List<MovementInput> inputs) {
        var input = inputs.stream().max(Comparator.comparingInt(i ->
                i.slots().stream().mapToInt(s -> s.options().size()).sum())).orElseThrow();
        for (int i = 0; i < 5; i++) policy.prepare(input).greedy();
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) policy.prepare(input).greedy();
        double preparation = (System.nanoTime() - start) / 20e6;
        var prepared = policy.prepare(input);
        Random random = new Random(7);
        for (int i = 0; i < 20; i++) prepared.logProbability(prepared.sample(random));
        start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < 100; i++) sum += prepared.logProbability(prepared.sample(random));
        require(Double.isFinite(sum), "Benchmark score");
        System.out.printf(Locale.ROOT, "Timing: units=%d options=%d prepare_plus_greedy_ms=%.3f prepared_sample_plus_score_ms=%.3f%n",
                input.slots().size(), input.slots().stream().mapToInt(s -> s.options().size()).sum(),
                preparation, (System.nanoTime() - start) / 100e6);
    }

    private static Nation nation(int id) { return Nation.valueOf(NeuralMovementSpec.POWERS.get(id)); }
    private static String name(int id) { return id == 0 ? "-" : NeuralMovementSpec.LOCATIONS.get(id); }
    private static int count(DataInputStream in, int low, int high) throws IOException {
        int value = in.readInt();
        if (value < low || value > high) throw new IOException("Invalid parity count: " + value);
        return value;
    }
    private static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private static void reseal(byte[] bytes) throws Exception {
        System.arraycopy(hash(Arrays.copyOf(bytes, bytes.length - 32)), 0, bytes, bytes.length - 32, 32);
    }
    private static void close(double actual, double expected, double tolerance, String message) {
        require(Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual - expected) <= tolerance,
                message + ": " + actual + " vs " + expected);
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked operation) {
        try { operation.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}