package testing.selfcheck;

import agents.encoding.EncodingPurpose;
import domain.Nation;
import io.catalog.SourceFingerprints;
import io.training.ValueOutcomeAudit;

import java.util.*;

/**
 * Synthetic source evidence only; no catalog, models or JDBC driver required.
 */
@Deprecated  // on arrival
public final class ValueOutcomeAuditSelfCheck {

    private static int checks;

    private ValueOutcomeAuditSelfCheck() { }

    public static void main(String[] args) {

        Map<String, Object> game = fixture();
        Map<String, Object> report = inspect(game);
        require(report.get("finding").equals("MATCHING_COUNTS_REQUIRE_ENDING_REVIEW"));
        require(report.get("counts_match").equals(true));
        require(report.get("value_target") == null);
        require(!ValueOutcomeAudit.json(report).contains("PRIVATE_PLAYER"));

        game.put("ScoringSystem", "OpenTributeFrac");
        report = inspect(game);
        require(report.get("scoring_label").equals("OpenTributeFrac"));
        require(report.get("finding").equals("MATCHING_COUNTS_REQUIRE_ENDING_REVIEW"));

        game.put("ResultSummary", Map.of("France", Map.of("CenterCount", 3)));
        require(inspect(game).get("finding").equals("INVALID_OR_MISSING_EVIDENCE"));

        game = fixture();
        Map<String, Object> summary = summary();
        summary.put("France", Map.of("CenterCount", 4));
        game.put("ResultSummary", summary);
        require(inspect(game).get("finding").equals("FINAL_COUNTS_DISAGREE"));

        game = fixture();
        game.put("GamePhases", List.of(Map.of("Phase", 19011, "Status", "Finished")));
        require(inspect(game).get("finding").equals("INVALID_OR_MISSING_EVIDENCE"));

        String payload = ValueOutcomeAudit.json(fixture());
        require(ValueOutcomeAudit.inspect("DIPLOBN", "7", payload, "0".repeat(64))
                .get("finding").equals("PAYLOAD_HASH_MISMATCH"));
        require(ValueOutcomeAudit.inspect("OTHER", "7", payload,
                SourceFingerprints.sha256Hex(payload)).get("finding").equals("UNSUPPORTED_SOURCE"));
        require(ValueOutcomeAudit.inspect("DIPLOBN", "7", "{",
                SourceFingerprints.sha256Hex("{")).get("finding").equals("INVALID_JSON"));

        for (int key = 0; key < 100; key++) {
            String hash = SourceFingerprints.sha256Hex("7\nDIPLOBN\n" + key);
            int bucket = new java.math.BigInteger(hash.substring(0, 16), 16)
                    .mod(java.math.BigInteger.valueOf(100)).intValue();
            EncodingPurpose expected = bucket < 80 ? EncodingPurpose.TRAIN
                    : bucket < 90 ? EncodingPurpose.VALIDATION : EncodingPurpose.TEST;
            require(ValueOutcomeAudit.split(7, "DIPLOBN", Integer.toString(key)) == expected);
        }

        System.out.println("ValueOutcomeAuditSelfCheck passed: " + checks + " checks");

    }

    private static Map<String, Object> fixture() {
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("ScoringSystem", "SumOfSquares");
        game.put("LimitType", "TimeLimited");
        game.put("DrawSize", 7);
        game.put("Player", "PRIVATE_PLAYER");
        game.put("ResultSummary", summary());
        game.put("GamePhases", List.of(Map.of(
                "Phase", 19011, "Status", "Finished", "Units", Map.of(),
                "SupplyCenters", Map.of("France", List.of("Par", "Bre", "Mar")),
                "Orders", Map.of())));
        return game;
    }

    private static Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Nation nation : Nation.values()) {
            String name = nation.name().charAt(0)
                    + nation.name().substring(1).toLowerCase(Locale.ROOT);
            summary.put(name, Map.of("CenterCount", nation == Nation.FRANCE ? 3 : 0));
        }
        return summary;
    }

    private static Map<String, Object> inspect(Map<String, Object> game) {
        String payload = ValueOutcomeAudit.json(game);
        return ValueOutcomeAudit.inspect(
                "DIPLOBN", "7", payload, SourceFingerprints.sha256Hex(payload));
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition)
            throw new AssertionError("Check " + checks + " failed");
    }

}