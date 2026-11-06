package ui;

import agents.play.GameRunner;
import contracts.OrderForm;
import domain.Constants;
import domain.Nation;
import domain.Province;
import game.*;
import game.record.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Exports recorded native play for the existing game-browser presentation.
 *
 * <p>The bundle is a read-only replay, independent of the DiploBN database.
 * It contains phase-start and post-resolution boards, submissions, local result
 * annotations, seeds, settings, and a final snapshot. It is not a GameRecord
 * deserializer or a trained-agent checkpoint.</p>
 */
public final class NativeGameReplay {

    private NativeGameReplay() { }

    public static void writeBundle(Path output, Path assets, GameRunner.Result result,
                                   ScoringSystem scoring, Map<String, String> settings) throws IOException {
        String html = Files.readString(assets.resolve("games.html"));
        if (!html.contains("data-source=\"catalog\""))
            throw new IOException("Game browser template has no source marker");
        String json = toJson(result, scoring, settings);
        Path destination = output.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.createDirectory(destination);
        Files.createDirectories(destination.resolve("ui/maps"));
        for (String asset : List.of("games.js", "games.css", "games-detectives.js",
                "games-detectives.css", "openings.css", "maps/standard.svg"))
            Files.copy(assets.resolve(asset), destination.resolve("ui").resolve(asset));
        Files.writeString(destination.resolve("replay.json"), json);
        Files.writeString(destination.resolve("index.html"),
                html.replace("data-source=\"catalog\"", "data-source=\"native\""));
    }

    public static String toJson(GameRunner.Result result, ScoringSystem scoring, Map<String, String> settings) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(scoring, "scoring");
        settings = Map.copyOf(settings);
        GameRecord record = result.record();
        StringBuilder out = new StringBuilder("{\"schemaVersion\":1,\"source\":\"NATIVE\",");
        field(out, "id", record.id().toString());
        out.append(',');
        field(out, "title", "Local agent game");
        out.append(',');
        field(out, "rulesetId", record.rulesetId());
        out.append(',');
        field(out, "scoring", scoring.name());
        out.append(',');
        field(out, "endReason", result.endReason().name());
        out.append(',');
        field(out, "winner", GameRunner.winner(result.finalState().board()).map(Enum::name).orElse(null));
        out.append(",\"settings\":{");
        boolean first = true;
        for (var entry : new TreeMap<>(settings).entrySet()) {
            if (!first) out.append(',');
            field(out, entry.getKey(), entry.getValue());
            first = false;
        }
        out.append("},\"colors\":{");
        first = true;
        for (Nation nation : Nation.values()) {
            if (!first) out.append(',');
            field(out, nation.name(), Constants.nationHex(nation));
            first = false;
        }
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values()) counts.put(nation, 0);
        for (Nation owner : result.finalState().board().owners().values()) counts.merge(owner, 1, Integer::sum);
        out.append("},\"finalScores\":{");
        first = true;
        for (var entry : scoring.score(counts).entrySet()) {
            if (!first) out.append(',');
            appendString(out, entry.getKey().name());
            out.append(':').append(entry.getValue());
            first = false;
        }
        out.append("},\"phases\":[");
        for (int index = 0; index < record.resolvedPhases().size(); index++) {
            if (index > 0) out.append(',');
            GameMoment after = index + 1 < record.resolvedPhases().size()
                    ? record.resolvedPhases().get(index + 1).gameMoment() : result.finalState().moment();
            appendPhase(out, record.resolvedPhases().get(index), result.decisionSeeds().get(index), after);
        }
        if (!record.resolvedPhases().isEmpty()) out.append(',');
        out.append("{\"status\":\"FINAL\",\"board\":").append(board(result.finalState().moment(), result.finalState().board()));
        return out.append(",\"orders\":[],\"annotations\":[],\"dislodgedUnits\":[],\"investigation\":null}]}").toString();
    }

    private static void appendPhase(StringBuilder out, ResolvedPhaseRecord phase, Map<Nation, Long> seeds,
                                    GameMoment after) {
        out.append("{\"status\":\"RESOLVED\",\"board\":").append(board(phase.gameMoment(), phase.boardBefore()));
        out.append(",\"boardAfter\":").append(board(after, phase.boardAfter()));
        out.append(',');
        field(out, "resolvedAt", phase.resolvedAt().toString());
        out.append(",\"agentSeeds\":{");
        boolean first = true;
        for (Nation nation : Nation.values()) {
            if (!seeds.containsKey(nation)) continue;
            if (!first) out.append(',');
            field(out, nation.name(), Long.toString(seeds.get(nation)));
            first = false;
        }
        List<? extends OrderForm> orders = switch (phase) {
            case MovementPhaseRecord movement -> movement.submittedOrders();
            case RetreatPhaseRecord retreats -> retreats.submittedOrders();
            case AdjustmentPhaseRecord adjustments -> adjustments.submittedOrders();
        };
        out.append("},\"orders\":[");
        for (int index = 0; index < orders.size(); index++) {
            if (index > 0) out.append(',');
            OrderForm order = orders.get(index);
            Province origin = switch (order) {
                case Order movement -> phase.boardBefore().locationOf(movement.unit());
                case RetreatOrder retreat -> ((RetreatPhaseRecord) phase).result().movementResult()
                        .dislodgements().get(retreat.unit()).displacedFrom();
                case DisbandOrder disband -> phase.boardBefore().locationOf(disband.unit());
                default -> order.origin();
            };
            out.append('{');
            field(out, "nation", order.owner().name());
            out.append(',');
            field(out, "type", order.orderType().name());
            out.append(',');
            field(out, "unitType", order.unitType() == null ? null : order.unitType().name());
            out.append(',');
            field(out, "origin", name(origin));
            out.append(',');
            field(out, "target", name(order.target()));
            out.append(',');
            field(out, "auxiliaryTarget", name(order.auxiliaryTarget()));
            out.append(',');
            field(out, "text", OrderForm.format(order, origin));
            out.append('}');
        }
        out.append("],\"dislodgedUnits\":[");
        if (phase instanceof RetreatPhaseRecord retreats) {
            first = true;
            for (var dislodged : retreats.result().movementResult().dislodgements().values()) {
                if (!first) out.append(',');
                out.append('{');
                field(out, "nation", dislodged.unit().owner().name());
                out.append(',');
                field(out, "type", dislodged.unit().unitType().name());
                out.append(',');
                field(out, "province", dislodged.displacedFrom().name());
                out.append('}');
                first = false;
            }
        }
        out.append("],\"annotations\":[");
        first = true;
        if (phase instanceof MovementPhaseRecord movement) {
            for (var entry : movement.result().outcomes().entrySet()) {
                if (!first) out.append(',');
                annotation(out, entry.getKey().owner(), phase.boardBefore().locationOf(entry.getKey()),
                        false, entry.getValue().succeeded(), entry.getValue().effectiveType().name());
                first = false;
            }
        } else if (phase instanceof RetreatPhaseRecord retreats) {
            for (var entry : retreats.result().outcomes().entrySet()) {
                if (!first) out.append(',');
                annotation(out, entry.getKey().owner(), retreats.result().movementResult()
                        .dislodgements().get(entry.getKey()).displacedFrom(), true,
                        entry.getValue().succeeded(), entry.getValue().status().name());
                first = false;
            }
        } else if (phase instanceof AdjustmentPhaseRecord adjustments) {
            for (var outcome : adjustments.result().outcomes()) {
                if (!first) out.append(',');
                Province origin = outcome.order() instanceof DisbandOrder disband
                        ? phase.boardBefore().locationOf(disband.unit()) : outcome.order().origin();
                annotation(out, outcome.order().owner(), origin, false, outcome.accepted(), outcome.status().name());
                first = false;
            }
        }
        out.append("],\"investigation\":null}");
    }

    private static String board(GameMoment moment, BoardState board) {
        return BoardSnapshotJsonWriter.toJson(BoardSnapshotMapper.from(moment, board));
    }

    private static void annotation(StringBuilder out, Nation nation, Province origin,
                                   boolean retreat, boolean successful, String reason) {
        out.append('{');
        field(out, "nation", nation.name());
        out.append(',');
        field(out, "origin", name(origin));
        out.append(",\"retreatOrder\":").append(retreat).append(",\"successful\":").append(successful).append(',');
        field(out, "reason", reason);
        out.append('}');
    }

    private static void field(StringBuilder out, String key, String value) {
        appendString(out, key);
        out.append(':');
        if (value == null) out.append("null");
        else appendString(out, value);
    }

    private static String name(Province province) {
        return province == null ? null : province.name();
    }

}
