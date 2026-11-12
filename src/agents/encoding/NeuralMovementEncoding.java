package agents.encoding;

import java.nio.*;
import java.util.Objects;

/**
 * Packed model inputs from a typed movement position. Targets and source
 * metadata are absent. Returned buffers are read-only views with independent
 * positions; preparing another example does not change an earlier encoding.
 */
public final class NeuralMovementEncoding {

    private final byte[] board, slots, options;
    private final int[] offsets;
    private final int power, phase, year;

    public NeuralMovementEncoding(MovementInput input) {
        Objects.requireNonNull(input, "input");
        power = NeuralMovementSpec.powerId(input.power());
        phase = NeuralMovementSpec.phaseId(input.moment().gamePhase());
        year = input.moment().year();
        board = new byte[NeuralMovementSpec.BOARD_ROWS * NeuralMovementSpec.BOARD_FIELDS];
        input.centers().forEach((location, owner) ->
                board[NeuralMovementSpec.boardRow(location) * NeuralMovementSpec.BOARD_FIELDS + 3]
                        = (byte) NeuralMovementSpec.powerId(owner));
        for (var unit : input.units()) {
            int row = NeuralMovementSpec.boardRow(unit.location()) * NeuralMovementSpec.BOARD_FIELDS;
            board[row] = (byte) NeuralMovementSpec.unitTypeId(unit.type());
            board[row + 1] = (byte) NeuralMovementSpec.powerId(unit.owner());
            board[row + 2] = (byte) NeuralMovementSpec.locationId(unit.location());
        }
        slots = new byte[input.slots().size()];
        offsets = new int[slots.length + 1];
        for (int slot = 0; slot < slots.length; slot++) {
            slots[slot] = (byte) NeuralMovementSpec.locationId(input.slots().get(slot).location());
            offsets[slot + 1] = Math.addExact(offsets[slot], input.slots().get(slot).options().size());
        }
        options = new byte[Math.multiplyExact(offsets[slots.length], NeuralMovementSpec.ORDER_FIELDS)];
        int position = 0;
        for (var slot : input.slots())
            for (var option : slot.options()) {
                options[position++] = (byte) NeuralMovementSpec.unitTypeId(option.unitType());
                options[position++] = (byte) NeuralMovementSpec.locationId(option.origin());
                options[position++] = (byte) NeuralMovementSpec.orderTypeId(option.type());
                options[position++] = (byte) NeuralMovementSpec.locationId(option.target());
                options[position++] = (byte) NeuralMovementSpec.locationId(option.auxiliary());
            }
    }

    public int power() { return power; }
    public int phase() { return phase; }
    public int year() { return year; }
    public float normalizedYear() { return (float) (year - NeuralMovementSpec.YEAR_ORIGIN) / NeuralMovementSpec.YEAR_SCALE; }
    public int units() { return slots.length; }
    public int optionCount() { return offsets[slots.length]; }
    public ByteBuffer board() { return ByteBuffer.wrap(board).asReadOnlyBuffer(); }
    public ByteBuffer slotLocations() { return ByteBuffer.wrap(slots).asReadOnlyBuffer(); }
    public ByteBuffer orderFields() { return ByteBuffer.wrap(options).asReadOnlyBuffer(); }
    public IntBuffer optionOffsets() { return IntBuffer.wrap(offsets).asReadOnlyBuffer(); }
}