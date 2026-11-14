package io.training;

import agents.encoding.EncodingPurpose;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import io.json.JsonAccess;
import io.json.JsonReader;
import misc.scoring.ScoringSystem;
import phase.UnitId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Provider-neutral value-game JSON and bounded, fingerprinted JSONL input.
 * Stored targets must agree with the current reward contract.
 */
public final class ValueDatasetJson {


    // Format \\

    public static final String SCHEMA = "jreferee-value-game-v1";
    public static final int MAX_LINE_CHARACTERS = 16 * 1024 * 1024;

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private ValueDatasetJson() { }

    public static String json(Object value) {
        return ValueOutcomeAudit.json(value);
    }

    public static Map<String, Object> object(Object value) {
        return JSON.object(value, "$");
    }

    public static Map<String, Object> parse(String text) {
        return object(JsonReader.read(text));
    }

    public static String text(Map<String, Object> object, String key) {
        return JSON.string(JSON.required(object, key, "$"), "$." + key);
    }

    public static long count(Map<String, Object> object, String key) {

        Object value = JSON.required(object, key, "$");

        if (!(value instanceof Long || value instanceof Integer))
            throw new IllegalArgumentException("Expected integer: " + key);

        long count = ((Number) value).longValue();

        if (count < 0)
            throw new IllegalArgumentException("Negative count: " + key);

        return count;

    }

    private static Optional<String> optionalText(
            Map<String, Object> object, String key
    ) {
        Object value = JSON.required(object, key, "$");
        return value == null ? Optional.empty()
                : Optional.of(JSON.string(value, "$." + key));
    }


    // Encoding \\

    public static String encode(
            ValueDataset.GameExample game,
            EncodingPurpose split
    ) {

        Map<Nation, Double> rewards = game.assess(ValueRewardContract.defaults())
                .rewards().orElseThrow(() -> new IllegalArgumentException(
                        "Cannot serialize a rejected value game"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("split", split.name());
        out.put("source", game.source());
        out.put("game_key", game.gameKey());
        out.put("payload_sha256", game.payloadSha256());
        out.put("communication_type", game.communicationType().orElse(null));
        out.put("reward_contract", ValueRewardContract.VERSION);
        out.put("target", named(rewards));

        ValueOutcome outcome = game.outcome();
        Map<String, Object> ending = new LinkedHashMap<>();
        ending.put("termination", outcome.termination().name());
        ending.put("evidence", outcome.evidence().name());
        ending.put("moment", moment(outcome.moment()));
        ending.put("center_counts", named(outcome.centerCounts()));
        ending.put("source_scoring", outcome.sourceScoring().orElse(null));
        ending.put("source_limit", outcome.sourceLimit().orElse(null));
        ending.put("provenance", outcome.provenance());
        out.put("outcome", ending);

        List<Object> positions = new ArrayList<>();

        for (ValueDataset.Position position : game.positions()) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moment", moment(position.moment()));
            row.put("scoring", position.scoring().name());

            List<List<String>> units = new ArrayList<>();
            position.board().locations().forEach((unit, location) -> units.add(
                    List.of(unit.owner().name(), unit.unitType().name(), location.name())));
            units.sort(Comparator.comparing(unit -> unit.get(2)));
            row.put("units", units);

            Map<String, String> centers = new TreeMap<>();
            position.board().owners().forEach((location, owner) ->
                    centers.put(location.name(), owner.name()));
            row.put("centers", centers);
            positions.add(row);

        }

        out.put("positions", positions);
        String result = json(out);

        if (result.length() > MAX_LINE_CHARACTERS)
            throw new IllegalArgumentException("Value game exceeds the JSONL line limit");

        return result;

    }

    private static Map<String, Object> named(Map<Nation, ?> values) {
        Map<String, Object> result = new TreeMap<>();
        values.forEach((nation, value) -> result.put(nation.name(), value));
        return result;
    }

    private static List<Object> moment(GameMoment moment) {
        return List.of(moment.year(), moment.gamePhase().name());
    }


    // Decoding \\

    public static ValueDataset.GameExample decode(
            String line,
            EncodingPurpose expectedSplit
    ) {

        Map<String, Object> raw = parse(line);

        if (!SCHEMA.equals(text(raw, "schema"))
                || !expectedSplit.name().equals(text(raw, "split"))
                || !ValueRewardContract.VERSION.equals(text(raw, "reward_contract")))
            throw new IllegalArgumentException("Unsupported value schema, split or reward contract");

        Map<String, Object> ending = object(raw.get("outcome"));
        Map<String, Object> rawCounts = object(ending.get("center_counts"));
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);

        for (Map.Entry<String, Object> entry : rawCounts.entrySet())
            counts.put(Nation.valueOf(entry.getKey()),
                    JSON.integer(entry.getValue(), "$.outcome.center_counts"));

        ValueOutcome outcome = new ValueOutcome(
                ValueOutcome.Termination.valueOf(text(ending, "termination")),
                ValueOutcome.Evidence.valueOf(text(ending, "evidence")),
                readMoment(ending.get("moment")),
                counts,
                optionalText(ending, "source_scoring"),
                optionalText(ending, "source_limit"),
                text(ending, "provenance")
        );

        List<ValueDataset.Position> positions = new ArrayList<>();

        for (Object value : JSON.array(raw.get("positions"), "$.positions")) {

            Map<String, Object> row = object(value);
            Map<UnitId, Province> units = new LinkedHashMap<>();

            for (Object unitValue : JSON.array(row.get("units"), "$.units")) {

                List<Object> tuple = JSON.array(unitValue, "$.units[]");
                JSON.requireSize(tuple, 3, "$.units[]");

                Nation nation = Nation.valueOf(JSON.string(tuple.get(0), "$.unit.nation"));
                UnitType type = UnitType.valueOf(JSON.string(tuple.get(1), "$.unit.type"));
                Province location = Province.valueOf(JSON.string(tuple.get(2), "$.unit.location"));
                UnitId unit = new UnitId(nation, type, location);

                if (units.putIfAbsent(unit, location) != null)
                    throw new IllegalArgumentException("Duplicate value-input unit");

            }

            Map<Province, Nation> centers = new EnumMap<>(Province.class);

            for (Map.Entry<String, Object> entry : object(row.get("centers")).entrySet()) {

                Province location = Province.valueOf(entry.getKey());

                if (!location.supplyCenter || Province.canonical(location) != location)
                    throw new IllegalArgumentException("Invalid value-input center");

                centers.put(location, Nation.valueOf(
                        JSON.string(entry.getValue(), "$.centers")));

            }

            positions.add(new ValueDataset.Position(
                    readMoment(row.get("moment")),
                    new BoardState(units, centers),
                    ScoringSystem.valueOf(text(row, "scoring"))
            ));

        }

        ValueDataset.GameExample game = new ValueDataset.GameExample(
                text(raw, "source"),
                text(raw, "game_key"),
                optionalText(raw, "communication_type"),
                text(raw, "payload_sha256"),
                positions,
                outcome
        );

        Map<Nation, Double> expected = game.assess(ValueRewardContract.defaults())
                .rewards().orElseThrow(() -> new IllegalArgumentException(
                        "Dataset contains a rejected outcome"));
        Map<String, Object> stored = object(raw.get("target"));

        if (!stored.keySet().equals(named(expected).keySet()))
            throw new IllegalArgumentException("Target must contain all seven nations");

        for (Nation nation : Nation.values()) {

            Object value = stored.get(nation.name());

            if (!(value instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || Math.abs(number.doubleValue() - expected.get(nation)) > 1e-12)
                throw new IllegalArgumentException("Stored target differs from the reward contract");

        }

        return game;

    }

    private static GameMoment readMoment(Object value) {
        List<Object> tuple = JSON.array(value, "$.moment");
        JSON.requireSize(tuple, 2, "$.moment");
        return new GameMoment(
                JSON.integer(tuple.get(0), "$.moment.year"),
                GamePhase.valueOf(JSON.string(tuple.get(1), "$.moment.phase"))
        );
    }


    // Fingerprinted input \\

    public static String hash(Path path) throws IOException {
        try (DigestInputStream input = new DigestInputStream(
                Files.newInputStream(path), digest())) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static final class Lines implements AutoCloseable {

        private final MessageDigest digest = digest();
        private final BufferedReader reader;
        private String verifiedHash;

        public Lines(Path path) throws IOException {
            this.reader = new BufferedReader(new InputStreamReader(
                    new DigestInputStream(Files.newInputStream(path), this.digest),
                    StandardCharsets.UTF_8.newDecoder()
            ));
        }

        public String next() throws IOException {

            if (this.verifiedHash != null)
                return null;

            StringBuilder line = new StringBuilder();

            while (true) {

                int character = this.reader.read();

                if (character == -1) {
                    this.verifiedHash = HexFormat.of().formatHex(this.digest.digest());
                    return line.isEmpty() ? null : line.toString();
                }

                if (character == '\n') {
                    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r')
                        line.setLength(line.length() - 1);
                    return line.toString();
                }

                if (line.length() == MAX_LINE_CHARACTERS)
                    throw new IOException("JSONL line exceeds the supported limit");

                line.append((char) character);

            }

        }

        public String hashAtEnd() {
            if (this.verifiedHash == null)
                throw new IllegalStateException("Input has not reached EOF");
            return this.verifiedHash;
        }

        @Override
        public void close() throws IOException {
            this.reader.close();
        }

    }

}