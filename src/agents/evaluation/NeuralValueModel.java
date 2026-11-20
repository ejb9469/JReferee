package agents.evaluation;

import agents.encoding.*;
import contracts.OrderForms;
import domain.Nation;
import game.BoardState;
import game.GameMoment;
import io.json.*;
import io.training.NeuralValueFiles;
import io.training.ValueRewardContract;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Immutable inference weights for supervised and self-play seven-power values.
 * This class does not replace terminal scoring or implement search evaluation.
 */
public class NeuralValueModel {


    // Architecture \\

    public static final String SCHEMA = "jreferee-value-java-v1";
    public static final String SELF_PLAY_SCHEMA = "jreferee-self-play-value-java-v1";
    public static final String SELF_PLAY_MODEL_SCHEMA = "jreferee-self-play-value-network-v1";

    public record Dataset(String fingerprint, String manifestSha256) { }

    public record SelfPlay(String trainingReportSha256, String initialCheckpointSha256,
                           long splitSeed, List<Dataset> datasets) {
        public SelfPlay {
            datasets = List.copyOf(datasets);
        }
    }

    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));
    private static final Map<String, Object> SPEC =
            JSON.object(JsonReader.read(NeuralMovementSpec.toJson()), "$");

    private static final    int[] ROWS = integers("location_board_rows");
    private static final    int[] POSITIONS = integers("board_locations");
    private static final    int[] TERRAIN = integers("terrain");
    private static final    int[] HOMES = integers("home_owners");
    private static final    int[] CENTERS = integers("supply_centers");

    private final           Map<String, double[][]> weights;
    private final           double[][]              adjacency = new double[75][75];
    private final           int                     hidden, epoch;
    private final           String                  sha256, checkpointSha256, datasetFingerprint;
    private final           Optional<String>        cacheManifestSha256;        // TODO: Optional<> used as a field is apparently ill-advised,
    private final           Optional<SelfPlay>      selfPlay;                   //      ... this is sensitive infrastructure, so it is probably best to heed this advice.

    private NeuralValueModel(
            Map<String, double[][]> weights, int hidden, int epoch,
            String sha256, String checkpoint, String dataset,
            Optional<String> cache, Optional<SelfPlay> selfPlay
    ) {

        this.weights = Map.copyOf(weights);
        this.hidden = hidden;
        this.epoch = epoch;
        this.sha256 = sha256;
        this.checkpointSha256 = checkpoint;
        this.datasetFingerprint = dataset;
        this.cacheManifestSha256 = cache;
        this.selfPlay = selfPlay;

        for (int row = 0; row < 75; row++)
            this.adjacency[row][row] = 1;

        for (String name : List.of("army_direct", "fleet_direct"))
            for (Object value : JSON.array(SPEC.get(name), name)) {
                List<Object> edge = JSON.array(value, name);
                this.adjacency[ROWS[JSON.integer(edge.get(0), name)]]
                        [ROWS[JSON.integer(edge.get(1), name)]] = 1;
            }

        for (double[] row : this.adjacency) {
            double count = Arrays.stream(row).sum();
            for (int column = 0; column < row.length; column++)
                row[column] = (float) (row[column] / count);
        }

    }


    // Loading \\

    public static NeuralValueModel load(Path path) throws IOException {

        byte[] file = NeuralValueFiles.read(path);

        try (DataInputStream input = NeuralValueFiles.payload(file)) {

            String magic = new String(input.readNBytes(8), StandardCharsets.US_ASCII);
            boolean learned = magic.equals("JREFSVL1");
            if ((!learned && !magic.equals("JREFVAL1"))
                    || !NeuralValueFiles.text(input).equals(learned ? SELF_PLAY_SCHEMA : SCHEMA)
                    || !NeuralValueFiles.text(input).equals(
                    learned ? SELF_PLAY_MODEL_SCHEMA : "jreferee-value-network-v1")
                    || !NeuralValueFiles.text(input).equals(ValueRewardContract.VERSION)
                    || !NeuralValueFiles.text(input).equals(NeuralValueEncoding.specificationJson())
                    || !NeuralValueFiles.identity(input).equals(specificationSha256()))
                throw new IOException("Unsupported value architecture, reward or specification");

            String checkpoint = NeuralValueFiles.identity(input);
            String dataset = NeuralValueFiles.identity(input);
            Optional<String> cache = learned ? Optional.empty() : Optional.of(NeuralValueFiles.identity(input));
            Optional<SelfPlay> selfPlay = learned ? Optional.of(readSelfPlay(input, dataset)) : Optional.empty();
            int epoch = input.readInt(), hidden = input.readInt();

            if (epoch < (learned ? 0 : 1) || epoch > 10000 || hidden < 16 || hidden > 512)
                throw new IOException("Invalid value epoch or hidden size");

            Map<String, int[]> shapes = shapes(hidden);
            if (input.readInt() != shapes.size())
                throw new IOException("Wrong value parameter count");

            Map<String, double[][]> weights = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> entry : shapes.entrySet()) {

                int[] shape = entry.getValue();
                if (!NeuralValueFiles.text(input).equals(entry.getKey())
                        || input.readInt() != shape.length)
                    throw new IOException("Unexpected value tensor: " + entry.getKey());
                for (int dimension : shape)
                    if (input.readInt() != dimension)
                        throw new IOException("Wrong value tensor shape: " + entry.getKey());

                double[][] tensor = new double[shape.length == 1 ? 1 : shape[0]]
                        [shape[shape.length - 1]];
                for (double[] row : tensor)
                    for (int column = 0; column < row.length; column++) {
                        row[column] = input.readFloat();
                        if (!Double.isFinite(row[column]))
                            throw new IOException("Nonfinite value parameter");
                    }
                weights.put(entry.getKey(), tensor);

            }

            if (input.read() != -1)
                throw new IOException("Trailing value model data");

            return new NeuralValueModel(weights, hidden, epoch,
                    NeuralValueFiles.sha256(file), checkpoint, dataset, cache, selfPlay);

        } catch (EOFException exception) {
            throw new IOException("Truncated value model: " + path, exception);
        }

    }

    private static SelfPlay readSelfPlay(DataInputStream input, String cohort) throws IOException {

        String training = NeuralValueFiles.identity(input);
        String initial = NeuralValueFiles.identity(input);
        long splitSeed = input.readLong();
        int count = input.readInt();
        if (count < 1 || count > 1024)
            throw new IOException("Invalid self-play value dataset count");
        ByteArrayOutputStream identity = new ByteArrayOutputStream();
        identity.write(("jreferee-self-play-value-cohort-v1\n" + splitSeed + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        List<Dataset> datasets = new ArrayList<>();
        String previous = "";
        for (int index = 0; index < count; index++) {
            String fingerprint = NeuralValueFiles.identity(input);
            String manifest = NeuralValueFiles.identity(input);
            if (fingerprint.compareTo(previous) <= 0)
                throw new IOException("Unsorted or repeated self-play value dataset");
            previous = fingerprint;
            datasets.add(new Dataset(fingerprint, manifest));
            identity.write(HexFormat.of().parseHex(fingerprint));
            identity.write(HexFormat.of().parseHex(manifest));
        }
        if (!NeuralValueFiles.sha256(identity.toByteArray()).equals(cohort))
            throw new IOException("Self-play value cohort checksum mismatch");
        return new SelfPlay(training, initial, splitSeed, datasets);

    }


    // Prediction \\

    public Map<Nation, Double> predict(GameMoment moment, BoardState state) {

        Objects.requireNonNull(state, "state");
        if (state.locations().size() > 34)
            throw new IllegalArgumentException("More than 34 standing units");
        state.locations().forEach((unit, location) -> {
            if (!OrderForms.isValidUnitLocation(unit.unitType(), location))
                throw new IllegalArgumentException("Invalid value-input unit location: " + location);
        });

        NeuralValueEncoding input = new NeuralValueEncoding(moment, state);
        double[] context = join(embedding("phase", input.phase()),
                new double[]{input.normalizedYear()});
        double[][] board = new double[75][];
        var fields = input.board();

        for (int row = 0; row < 75; row++) {
            double[] local = join(
                    embedding("unit", Byte.toUnsignedInt(fields.get(row * 4))),
                    embedding("power", Byte.toUnsignedInt(fields.get(row * 4 + 1))),
                    embedding("location", Byte.toUnsignedInt(fields.get(row * 4 + 2))),
                    embedding("power", Byte.toUnsignedInt(fields.get(row * 4 + 3))),
                    embedding("location", POSITIONS[row]), embedding("terrain", TERRAIN[row]),
                    embedding("power", HOMES[row]), new double[]{CENTERS[row]}, context);
            board[row] = affine("board_in", local);
            relu(board[row]);
        }

        for (int layer = 0; layer < 2; layer++) {
            double[][] next = new double[75][];
            for (int row = 0; row < 75; row++) {
                double[] neighbors = new double[this.hidden];
                for (int column = 0; column < 75; column++)
                    if (this.adjacency[row][column] != 0)
                        for (int h = 0; h < this.hidden; h++)
                            neighbors[h] += this.adjacency[row][column] * board[column][h];
                next[row] = affine("layers." + layer, join(board[row], neighbors));
                for (int h = 0; h < this.hidden; h++)
                    next[row][h] += board[row][h];
                relu(next[row]);
            }
            board = next;
        }

        double[] mean = new double[this.hidden];
        for (double[] row : board)
            for (int h = 0; h < this.hidden; h++)
                mean[h] += row[h] / 75;
        double[] head = affine("head.0", join(mean, context));
        relu(head);
        double[] values = affine("head.2", head);
        double maximum = Arrays.stream(values).max().orElseThrow();
        double total = 0;
        for (int index = 0; index < values.length; index++) {
            values[index] = Math.exp(values[index] - maximum);
            total += values[index];
        }
        if (!Double.isFinite(total) || total <= 0)
            throw new IllegalStateException("Nonfinite value prediction");

        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (int index = 0; index < values.length; index++)
            result.put(NeuralValueEncoding.OUTPUT_NATIONS.get(index), values[index] / total);
        return Collections.unmodifiableMap(result);

    }


    // Numeric helpers \\

    private double[] embedding(String name, int id) {
        return this.weights.get(name + ".weight")[id];
    }

    private double[] affine(String name, double[] input) {
        double[][] weight = this.weights.get(name + ".weight");
        double[] output = this.weights.get(name + ".bias")[0].clone();
        for (int row = 0; row < weight.length; row++)
            for (int column = 0; column < input.length; column++)
                output[row] += weight[row][column] * input[column];
        return output;
    }

    private static void relu(double[] values) {
        for (int index = 0; index < values.length; index++)
            values[index] = Math.max(0, values[index]);
    }

    private static double[] join(double[]... parts) {
        double[] result = new double[Arrays.stream(parts).mapToInt(part -> part.length).sum()];
        int offset = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static int[] integers(String name) {
        return JSON.array(SPEC.get(name), name).stream()
                .mapToInt(value -> JSON.integer(value, name)).toArray();
    }

    private static Map<String, int[]> shapes(int hidden) {
        Map<String, int[]> result = new LinkedHashMap<>();
        result.put("unit.weight", new int[]{3, 4});
        result.put("power.weight", new int[]{8, 8});
        result.put("location.weight", new int[]{82, 16});
        result.put("phase.weight", new int[]{4, 4});
        result.put("terrain.weight", new int[]{3, 4});
        String[] layers = {"board_in", "layers.0", "layers.1", "head.0", "head.2"};
        int[] inputs = {70, 2 * hidden, 2 * hidden, hidden + 5, hidden};
        for (int index = 0; index < layers.length; index++) {
            int outputs = index == layers.length - 1 ? 7 : hidden;
            result.put(layers[index] + ".weight", new int[]{outputs, inputs[index]});
            result.put(layers[index] + ".bias", new int[]{outputs});
        }
        return result;
    }


    // Identity \\

    public static String specificationSha256() {
        return NeuralValueFiles.sha256(
                NeuralValueEncoding.specificationJson().getBytes(StandardCharsets.UTF_8));
    }

    public String sha256() { return this.sha256; }
    public String checkpointSha256() { return this.checkpointSha256; }
    public String datasetFingerprint() { return this.datasetFingerprint; }
    public String cacheManifestSha256() {
        return this.cacheManifestSha256.orElseThrow(() ->
                new IllegalStateException("Self-play values use cohort and training receipts"));
    }
    public Optional<SelfPlay> selfPlay() { return this.selfPlay; }
    public int epoch() { return this.epoch; }
    public int hidden() { return this.hidden; }

}