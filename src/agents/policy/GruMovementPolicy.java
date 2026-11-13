package agents.policy;

import agents.encoding.*;
import io.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Inference for the frozen PyTorch movement GRU.
 * Weights and prepared positions are immutable; decoder state belongs to each call.
 */
public final class GruMovementPolicy implements MovementPolicy {

    public static final String SCHEMA = "jreferee-gru-java-v1";
    private static final JsonAccess JSON = new JsonAccess((path, message) -> new IllegalArgumentException(path + ": " + message));
    private static final Map<String, Object> SPEC = JSON.object(JsonReader.read(NeuralMovementSpec.toJson()), "$");
    private static final int[] ROWS = integers("location_board_rows");
    private final Map<String, double[][]> weights;
    private final double[][] adjacency = new double[75][75];
    private final double[][][] categorical = new double[5][][];
    private final int hidden, epoch;
    private final String sha256, checkpointSha256, datasetIndexSha256;

    private GruMovementPolicy(Map<String, double[][]> weights, int hidden, int epoch, String hash, String checkpoint, String dataset) {
        this.weights = weights;
        this.hidden = hidden;
        this.epoch = epoch;
        sha256 = hash;
        checkpointSha256 = checkpoint;
        datasetIndexSha256 = dataset;
        for (int row = 0; row < 75; row++) adjacency[row][row] = 1;
        for (String name : List.of("army_direct", "fleet_direct"))
            for (Object value : JSON.array(SPEC.get(name), name)) {
                var edge = JSON.array(value, name);
                adjacency[ROWS[JSON.integer(edge.get(0), name)]][ROWS[JSON.integer(edge.get(1), name)]] = 1;
            }
        for (double[] row : adjacency) {
            double count = Arrays.stream(row).sum();
            for (int col = 0; col < 75; col++) row[col] = (float) (row[col] / count);
        }
        int offset = 0;
        String[] embeddings = {"unit", "location", "order", "location", "location"};
        for (int field = 0; field < embeddings.length; field++) {
            double[][] embedding = weights.get(embeddings[field] + ".weight");
            categorical[field] = new double[embedding.length][hidden];
            for (int id = 0; id < embedding.length; id++)
                categorical[field][id] = project(embedding[id], offset);
            offset += embedding[0].length;
        }
    }

    public int hidden() { return hidden; }
    public int epoch() { return epoch; }
    public String sha256() { return sha256; }
    public String checkpointSha256() { return checkpointSha256; }
    public String datasetIndexSha256() { return datasetIndexSha256; }

    public static GruMovementPolicy load(Path path) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("GRU file exceeds 64 MiB");
        byte[] file = Files.readAllBytes(path);
        if (file.length < 40 || !MessageDigest.isEqual(hash(Arrays.copyOf(file, file.length - 32)),
                Arrays.copyOfRange(file, file.length - 32, file.length)))
            throw new IOException("GRU checksum or file length is invalid: " + path);
        try (var in = new DataInputStream(new ByteArrayInputStream(file, 0, file.length - 32))) {
            if (!Arrays.equals(in.readNBytes(8), "JREFGRU1".getBytes(StandardCharsets.US_ASCII))
                    || !text(in).equals(SCHEMA) || !text(in).equals("jreferee-gru-policy-v1")
                    || !text(in).equals(NeuralMovementSpec.toJson())
                    || !HexFormat.of().formatHex(in.readNBytes(32)).equals(NeuralMovementSpec.sha256()))
                throw new IOException("Unsupported GRU architecture or input specification");
            String checkpoint = HexFormat.of().formatHex(in.readNBytes(32));
            String dataset = HexFormat.of().formatHex(in.readNBytes(32));
            if (in.readNBytes(32).length != 32) throw new EOFException();
            int epoch = in.readInt(), hidden = in.readInt();
            if (epoch < 1 || epoch > 10000 || hidden < 16 || hidden > 512) throw new IOException("Invalid GRU dimensions/epoch");
            var shapes = shapes(hidden);
            if (in.readInt() != shapes.size()) throw new IOException("Wrong GRU tensor count");
            Map<String, double[][]> weights = new LinkedHashMap<>();
            for (var entry : shapes.entrySet()) {
                int[] shape = entry.getValue();
                if (!text(in).equals(entry.getKey()) || in.readInt() != shape.length)
                    throw new IOException("Unexpected GRU tensor: " + entry.getKey());
                for (int dimension : shape)
                    if (in.readInt() != dimension) throw new IOException("Wrong tensor shape: " + entry.getKey());
                double[][] tensor = new double[shape.length == 1 ? 1 : shape[0]][shape[shape.length - 1]];
                for (double[] row : tensor)
                    for (int col = 0; col < row.length; col++) {
                        row[col] = in.readFloat();
                        if (!Double.isFinite(row[col])) throw new IOException("Nonfinite GRU weight");
                    }
                weights.put(entry.getKey(), tensor);
            }
            if (in.read() != -1) throw new IOException("Trailing GRU data");
            return new GruMovementPolicy(weights, hidden, epoch, HexFormat.of().formatHex(hash(file)), checkpoint, dataset);
        } catch (EOFException exception) {
            throw new IOException("Truncated GRU file: " + path, exception);
        }
    }

    @Override
    public Prepared prepare(MovementInput input) {
        return new Position(Objects.requireNonNull(input, "input"));
    }

    private final class Position implements Prepared {
        private final MovementInput input;
        private final double[][] board;
        private final double[] initial;
        private final double[][][] options;
        private final double[][] biases;
        private final int[] issuing;

        private Position(MovementInput input) {
            this.input = input;
            var encoded = new NeuralMovementEncoding(input);
            double[] context = join(embedding("power", encoded.power()), embedding("phase", encoded.phase()),
                    new double[]{encoded.normalizedYear()});
            int[] positions = integers("board_locations"), terrain = integers("terrain"), homes = integers("home_owners");
            int[] centers = integers("supply_centers");
            double[][] local = new double[75][];
            var fields = encoded.board();
            for (int row = 0; row < 75; row++) {
                double[] values = join(embedding("unit", fields.get(row * 4)), embedding("power", fields.get(row * 4 + 1)),
                        embedding("location", fields.get(row * 4 + 2)), embedding("power", fields.get(row * 4 + 3)),
                        embedding("location", positions[row]), embedding("terrain", terrain[row]),
                        embedding("power", homes[row]), new double[]{centers[row]}, context);
                local[row] = affine("board_in", values);
                relu(local[row]);
            }
            for (int layer = 0; layer < 2; layer++) {
                double[][] next = new double[75][];
                for (int row = 0; row < 75; row++) {
                    double[] neighbors = new double[hidden];
                    for (int col = 0; col < 75; col++)
                        if (adjacency[row][col] != 0)
                            for (int h = 0; h < hidden; h++) neighbors[h] += adjacency[row][col] * local[col][h];
                    next[row] = affine("board_layers." + layer, join(local[row], neighbors));
                    for (int h = 0; h < hidden; h++) next[row][h] += local[row][h];
                    relu(next[row]);
                }
                local = next;
            }
            board = Arrays.copyOf(local, 76);
            board[75] = new double[hidden];
            double[] mean = new double[hidden];
            for (double[] row : local)
                for (int h = 0; h < hidden; h++) mean[h] += row[h] / 75;
            initial = affine("initial", join(mean, context));
            tanh(initial);
            // The order projection is linear before tanh; reuse its board and categorical terms.
            double[][][] projected = new double[3][76][];
            for (int role = 0; role < 3; role++)
                for (int row = 0; row < 76; row++) projected[role][row] = project(board[row], 60 + role * hidden);
            options = new double[encoded.units()][][];
            biases = new double[encoded.units()][];
            issuing = new int[encoded.units()];
            var orders = encoded.orderFields();
            var offsets = encoded.optionOffsets();
            for (int slot = 0; slot < options.length; slot++) {
                issuing[slot] = ROWS[encoded.slotLocations().get(slot)];
                int first = offsets.get(slot), count = offsets.get(slot + 1) - first;
                options[slot] = new double[count][hidden];
                biases[slot] = new double[count];
                for (int option = 0; option < count; option++) {
                    double[] values = options[slot][option];
                    System.arraycopy(weights.get("order_in.bias")[0], 0, values, 0, hidden);
                    for (int field = 0; field < 5; field++) {
                        int id = orders.get((first + option) * 5 + field);
                        add(values, categorical[field][id]);
                        if (field == 1 || field == 3 || field == 4)
                            add(values, projected[field == 1 ? 0 : field - 2][id == 0 ? 75 : ROWS[id]]);
                    }
                    tanh(values);
                    biases[slot][option] = affine("bias", values)[0];
                }
            }
        }

        @Override public MovementInput input() { return input; }

        @Override public double[] logProbabilities(List<Integer> prefix) {
            input.requirePrefix(prefix);
            if (prefix.size() == options.length) throw new IllegalArgumentException("The action is already complete");
            double[] state = initial;
            for (int slot = 0; slot < prefix.size(); slot++) state = advance(state, options[slot][prefix.get(slot)]);
            return probabilities(prefix.size(), state);
        }

        @Override public double logProbability(List<Integer> action) {
            input.requireAction(action);
            double result = 0;
            double[] state = initial;
            for (int slot = 0; slot < action.size(); slot++) {
                result += probabilities(slot, state)[action.get(slot)];
                if (slot + 1 < action.size()) state = advance(state, options[slot][action.get(slot)]);
            }
            return result;
        }

        @Override public List<Integer> greedy() { return choose(null); }
        @Override public List<Integer> sample(RandomGenerator random) { return choose(Objects.requireNonNull(random, "random")); }

        private List<Integer> choose(RandomGenerator random) {
            List<Integer> action = new ArrayList<>();
            double[] state = initial;
            for (int slot = 0; slot < options.length; slot++) {
                double[] logp = probabilities(slot, state);
                int selected = 0;
                if (random == null) {
                    for (int index = 1; index < logp.length; index++)
                        if (logp[index] > logp[selected]) selected = index;
                } else {
                    double draw = random.nextDouble(), sum = 0;
                    int last = -1;
                    selected = -1;
                    for (int index = 0; index < logp.length; index++) {
                        double mass = Math.exp(logp[index]);
                        if (mass == 0) continue;
                        last = index;
                        sum += mass;
                        if (selected < 0 && draw < sum) selected = index;
                    }
                    if (selected < 0) selected = last;
                }
                action.add(selected);
                if (slot + 1 < options.length) state = advance(state, options[slot][selected]);
            }
            return List.copyOf(action);
        }

        private double[] probabilities(int slot, double[] state) {
            double[] query = affine("query", join(state, board[issuing[slot]]));
            tanh(query);
            double[] scores = biases[slot].clone();
            for (int option = 0; option < scores.length; option++) {
                scores[option] += dot(options[slot][option], query) / Math.sqrt(hidden);
                if (!Double.isFinite(scores[option])) throw new IllegalStateException("Nonfinite GRU score");
            }
            double max = Arrays.stream(scores).max().orElseThrow(), sum = 0;
            for (double score : scores) sum += Math.exp(score - max);
            double normalizer = Math.log(sum);
            for (int index = 0; index < scores.length; index++) scores[index] = (scores[index] - max) - normalizer;
            MovementPolicy.requireDistribution(scores, scores.length);
            return scores;
        }
    }

    private double[] advance(double[] state, double[] order) {
        double[] input = affine("gru", "weight_ih", "bias_ih", order);
        double[] recurrent = affine("gru", "weight_hh", "bias_hh", state);
        double[] next = new double[hidden];
        // PyTorch packs reset, update, new gates; reset multiplies the recurrent new-gate bias too.
        for (int h = 0; h < hidden; h++) {
            double reset = sigmoid(input[h] + recurrent[h]);
            double update = sigmoid(input[hidden + h] + recurrent[hidden + h]);
            double value = Math.tanh(input[2 * hidden + h] + reset * recurrent[2 * hidden + h]);
            next[h] = (1 - update) * value + update * state[h];
        }
        return next;
    }

    private double[] embedding(String name, int id) { return weights.get(name + ".weight")[id]; }
    private double[] affine(String name, double[] input) { return affine(name, "weight", "bias", input); }
    private double[] affine(String name, String weight, String bias, double[] input) {
        double[][] matrix = weights.get(name + "." + weight);
        double[] result = weights.get(name + "." + bias)[0].clone();
        for (int row = 0; row < result.length; row++) result[row] += dot(matrix[row], input);
        return result;
    }
    private double[] project(double[] values, int offset) {
        double[][] matrix = weights.get("order_in.weight");
        double[] output = new double[hidden];
        for (int row = 0; row < hidden; row++)
            for (int col = 0; col < values.length; col++) output[row] += matrix[row][offset + col] * values[col];
        return output;
    }
    private static double dot(double[] a, double[] b) {
        double result = 0;
        for (int index = 0; index < a.length; index++) result += a[index] * b[index];
        return result;
    }
    private static void add(double[] a, double[] b) { for (int i = 0; i < a.length; i++) a[i] += b[i]; }
    private static void relu(double[] values) { for (int i = 0; i < values.length; i++) values[i] = Math.max(0, values[i]); }
    private static void tanh(double[] values) { for (int i = 0; i < values.length; i++) values[i] = Math.tanh(values[i]); }
    private static double sigmoid(double value) { return 1 / (1 + Math.exp(-value)); }
    private static double[] join(double[]... parts) {
        double[] result = new double[Arrays.stream(parts).mapToInt(p -> p.length).sum()];
        int offset = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
    private static int[] integers(String name) {
        return JSON.array(SPEC.get(name), name).stream().mapToInt(v -> JSON.integer(v, name)).toArray();
    }
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 32768) throw new IOException("Invalid GRU string length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static Map<String, int[]> shapes(int h) {
        Map<String, int[]> result = new LinkedHashMap<>();
        result.put("location.weight", new int[]{82, 16});
        result.put("unit.weight", new int[]{3, 4});
        result.put("power.weight", new int[]{8, 8});
        result.put("order.weight", new int[]{5, 8});
        result.put("phase.weight", new int[]{3, 4});
        result.put("terrain.weight", new int[]{3, 4});
        String[] layers = {"board_in", "board_layers.0", "board_layers.1", "initial", "order_in", "query", "bias"};
        int[] inputs = {78, 2 * h, 2 * h, h + 13, 60 + 3 * h, 2 * h, h};
        for (int i = 0; i < layers.length; i++) {
            int output = i == layers.length - 1 ? 1 : h;
            result.put(layers[i] + ".weight", new int[]{output, inputs[i]});
            result.put(layers[i] + ".bias", new int[]{output});
        }
        result.put("gru.weight_ih", new int[]{3 * h, h});
        result.put("gru.weight_hh", new int[]{3 * h, h});
        result.put("gru.bias_ih", new int[]{3 * h});
        result.put("gru.bias_hh", new int[]{3 * h});
        return result;
    }
}