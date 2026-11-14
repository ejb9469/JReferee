package agents.encoding;

import domain.Constants;
import domain.Nation;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Board-only value inputs shared by stored examples and live positions.
 * Pending retreats require information not represented by BoardState.
 */
public final class NeuralValueEncoding {


    // Specification \\

    public static final String SCHEMA = "jreferee-neural-value-v1";
    public static final int BOARD_BYTES =
            NeuralMovementSpec.BOARD_ROWS * NeuralMovementSpec.BOARD_FIELDS;

    public static final List<Nation> OUTPUT_NATIONS = NeuralMovementSpec.POWERS
            .subList(1, NeuralMovementSpec.POWERS.size())
            .stream().map(Nation::valueOf).toList();

    public static String specificationJson() {

        return "{\"schema\":\"" + SCHEMA + "\""
                + ",\"board_specification\":" + NeuralMovementSpec.toJson().strip()
                + ",\"phases\":[\"NONE\",\"SPRING_MOVEMENT\",\"FALL_MOVEMENT\",\"WINTER_ADJUSTMENT\"]"
                + ",\"context_fields\":[\"phase\",\"year\"]"
                + ",\"output_powers\":[" + String.join(",", OUTPUT_NATIONS.stream()
                .map(nation -> "\"" + nation.name() + "\"").toList()) + "]"
                + ",\"year_origin\":" + NeuralMovementSpec.YEAR_ORIGIN
                + ",\"year_scale\":" + NeuralMovementSpec.YEAR_SCALE
                + ",\"year_transform\":\"float32(year - year_origin) / float32(year_scale); no clipping\""
                + "}\n";

    }


    // Input \\

    private final byte[] board;
    private final int phase, year;

    public NeuralValueEncoding(GameMoment moment, BoardState state) {

        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(state, "state");

        if (moment.year() < Constants.STARTING_YEAR)
            throw new IllegalArgumentException("Value position precedes the starting year");

        this.phase = phaseId(moment.gamePhase());
        this.year = moment.year();
        this.board = new byte[BOARD_BYTES];

        state.owners().forEach((location, owner) ->
                this.board[NeuralMovementSpec.boardRow(location) * 4 + 3] =
                        (byte) NeuralMovementSpec.powerId(owner));

        state.locations().forEach((unit, location) -> {

            int row = NeuralMovementSpec.boardRow(location) * 4;
            this.board[row] = (byte) NeuralMovementSpec.unitTypeId(unit.unitType());
            this.board[row + 1] = (byte) NeuralMovementSpec.powerId(unit.owner());
            this.board[row + 2] = (byte) NeuralMovementSpec.locationId(location);

        });

    }

    private static int phaseId(GamePhase phase) {

        return switch (phase) {
            case SPRING_MOVEMENT -> 1;
            case FALL_MOVEMENT -> 2;
            case WINTER_ADJUSTMENT -> 3;
            case SPRING_RETREAT, FALL_RETREAT ->
                    throw new IllegalArgumentException("Pending retreats are not value inputs");
        };

    }


    // Access \\

    public ByteBuffer board() {
        return ByteBuffer.wrap(this.board).asReadOnlyBuffer();
    }

    public int phase() {
        return this.phase;
    }

    public int year() {
        return this.year;
    }

    public float normalizedYear() {
        return (float) (this.year - NeuralMovementSpec.YEAR_ORIGIN)
                / NeuralMovementSpec.YEAR_SCALE;
    }

}