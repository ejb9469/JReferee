package game;

import domain.Nation;
import game.press.PressExchange;
import game.press.PressMessage;
import game.press.PressProcedure;
import phase.Order;
import phase.PhaseInput;
import phase.PhaseResult;
import phase.Processor;
import phase.adjustments.AdjustmentInput;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentProcessor;
import phase.adjustments.AdjustmentResult;
import phase.movement.MovementInput;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatInput;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;
import static adjudication.util.Constants.STARTING_YEAR;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;


/**
 * Stateful Diplomacy game (phase-transition) orchestrator.
 *
 * <p>`Game` owns the current BoardState, year, and phase. The individual phase
 * `Processor`s remain responsible for their own adjudication rules.<br>
 * `Game` builds the correct input, invokes the appropriate processor, applies the result to
 * the persistent board, and advances to the next phase.</p>
 */
public final class Game {


    private final Processor<MovementInput, MovementResult>
            movementProcessor;

    private final Processor<RetreatInput, RetreatResult>
            retreatProcessor;

    private final Processor<AdjustmentInput, AdjustmentResult>
            adjustmentProcessor;

    private final PressExchange pressExchange;

    private final List<Processor<? extends PhaseInput, ? extends PhaseResult>>
            processorHistory = new ArrayList<>();

    private BoardState board;
    private int year;
    private GamePhase phase;

    /*
     * Stored only while a retreat phase is active. Retreat processing needs
     * the complete MovementResult for its dislodgement and standoff facts.
     */
    private MovementResult pendingMovement;

    /*
     * Stored after the Fall turn ends and consumed by Winter adjustments.
     * AdjustmentInput requires a RetreatResult even when no Fall units were
     * dislodged or no retreat orders were submitted.
     */
    private RetreatResult latestFallRetreat;

    /**
     * Creates a game at the start of Spring movement using standard phase
     * processor implementations.
     */
    public Game(int startingYear, BoardState initialBoard) {
        this(
                startingYear,
                initialBoard,
                new MovementProcessor(),
                new RetreatProcessor(),
                new AdjustmentProcessor(),
                new PressExchange());
    }

    /**
     * Creates a game at the start of Spring movement using supplied processors.
     *
     * <p>This overload permits a caller to supply a configured movement policy
     * or test double without making BoardState responsible for processors.</p>
     */
    public Game(
            int startingYear,
            BoardState initialBoard,
            Processor<MovementInput, MovementResult> movementProcessor,
            Processor<RetreatInput, RetreatResult> retreatProcessor,
            Processor<AdjustmentInput, AdjustmentResult> adjustmentProcessor
    ) {
        this(
                startingYear,
                initialBoard,
                movementProcessor,
                retreatProcessor,
                adjustmentProcessor,
                new PressExchange());
    }

    /**
     * Creates a game at the start of Spring movement using supplied processors
     * and press exchange.
     *
     * <p>The supplied exchange permits callers to pre-register an in-memory,
     * file, or external delivery procedure before the game begins.</p>
     */
    public Game(
            int startingYear,
            BoardState initialBoard,
            Processor<MovementInput, MovementResult> movementProcessor,
            Processor<RetreatInput, RetreatResult> retreatProcessor,
            Processor<AdjustmentInput, AdjustmentResult> adjustmentProcessor,
            PressExchange pressExchange
    ) {

        if (startingYear < STARTING_YEAR)
            throw new IllegalArgumentException("startingYear must be at least " + STARTING_YEAR);

        this.year = startingYear;
        this.phase = GamePhase.SPRING_MOVEMENT;
        this.board = Objects.requireNonNull(initialBoard, "initialBoard");

        this.movementProcessor = Objects.requireNonNull(movementProcessor, "movementProcessor");
        this.retreatProcessor = Objects.requireNonNull(retreatProcessor, "retreatProcessor");
        this.adjustmentProcessor = Objects.requireNonNull(adjustmentProcessor, "adjustmentProcessor");
        this.pressExchange = Objects.requireNonNull(pressExchange, "pressExchange");

    }

    public int year() {
        return year;
    }

    public GamePhase phase() {
        return phase;
    }

    public BoardState board() {
        return board;
    }

    /**
     * Creates, records, and delivers press in the current game year and phase.
     *
     * <p>Press does not modify board state, submitted orders, phase state, or
     * processor history.</p>
     */
    public PressMessage sendPress(
            Nation sender,
            Collection<Nation> recipients,
            String subject,
            String body
    ) {
        return pressExchange.send(
                year,
                phase,
                sender,
                recipients,
                subject,
                body);
    }

    /**
     * Registers a procedure for delivery of future press messages.
     */
    public void registerPressProcedure(
            PressProcedure procedure
    ) {
        pressExchange.registerProcedure(procedure);
    }

    /**
     * Removes a delivery procedure.
     *
     * @return whether the procedure had been registered
     */
    public boolean unregisterPressProcedure(
            PressProcedure procedure
    ) {
        return pressExchange.unregisterProcedure(procedure);
    }

    /**
     * Returns all accepted game press in chronological send order.
     */
    public List<PressMessage> pressHistory() {
        return pressExchange.history();
    }

    /**
     * Returns press visible to one nation in chronological send order.
     */
    public List<PressMessage> pressVisibleTo(
            Nation nation
    ) {
        return pressExchange.visibleTo(nation);
    }

    /**
     * Returns processors that have completed, non-errored phases, in chronological order.
     */
    public List<Processor<? extends PhaseInput, ? extends PhaseResult>>
    processorHistory()
    {
        return List.copyOf(processorHistory);  // copy of the references list
    }



    /**
     * Resolves the current Spring or Fall movement phase.
     *
     * <p>Units without submitted movement orders receive a HOLD order through
     * MovementProcessor. If no units are dislodged, the corresponding retreat
     * phase is skipped.</p>
     */
    public MovementResult resolveMovement(Collection<Order> submittedOrders) {

        requireMovementPhase();

        MovementResult result = movementProcessor
                .process(
                        new MovementInput(
                                board.locations(),
                                immutableList(submittedOrders, "submittedOrders"))
                );

        processorHistory.add(movementProcessor);  // add to history (ps references list)

        if (phase == GamePhase.SPRING_MOVEMENT) {
            resolveSpringMovement(result);
        } else {
            resolveFallMovement(result);
        }

        return result;

    }

    /**
     * Resolves the current Spring or Fall retreat phase.
     *
     * <p>Missing retreat orders are allowed. The RetreatProcessor destroys
     * dislodged units that have no valid retreat order.</p>
     */
    public RetreatResult resolveRetreats(Collection<RetreatOrder> submittedOrders) {

        requireRetreatPhase();

        if (pendingMovement == null)
            throw new IllegalStateException(
                    "Retreat phase has no pending movement result");

        RetreatResult result = retreatProcessor
                .process(
                        new RetreatInput(
                                pendingMovement,
                                immutableList(submittedOrders, "submittedOrders"))
                );

        processorHistory.add(retreatProcessor);  // add to history (ps references list)

        if (phase == GamePhase.SPRING_RETREAT) {
            board = board.after(result);
            pendingMovement = null;
            phase = GamePhase.FALL_MOVEMENT;
        } else {
            /*
             * Supply-center control changes only after the entire Fall turn.
             * This includes successful retreats into supply centers.
             */
            board = board.afterFallTurn(result);
            pendingMovement = null;
            latestFallRetreat = result;
            phase = GamePhase.WINTER_ADJUSTMENT;
        }

        return result;

    }

    /**
     * Resolves the current Winter adjustment phase.
     *
     * <p>Supply-center ownership has already been determined from the final
     * Fall board. The completed adjustment result becomes the next Spring
     * movement board.</p>
     */
    public AdjustmentResult resolveAdjustments(Collection<AdjustmentOrder> submittedOrders) {

        if (phase != GamePhase.WINTER_ADJUSTMENT)
            throw new IllegalStateException(
                    "Cannot resolve adjustments during " + phase);

        if (latestFallRetreat == null)
            throw new IllegalStateException(
                    "Winter adjustments require a Fall retreat result");

        AdjustmentResult result = adjustmentProcessor
                .process(
                        new AdjustmentInput(
                                latestFallRetreat,
                                board.owners(),
                                immutableList(submittedOrders, "submittedOrders"))
                );

        processorHistory.add(adjustmentProcessor);  // add to history (ps references list)

        board = board.after(result);
        latestFallRetreat = null;
        year++;
        phase = GamePhase.SPRING_MOVEMENT;

        return result;

    }

    private void resolveSpringMovement(MovementResult result) {

        /*
         * Spring occupation does not change supply-center control.
         */
        board = board.after(result);

        if (result.dislodgements().isEmpty()) {
            phase = GamePhase.FALL_MOVEMENT;
            return;
        }

        // do not add to history (ps references list) -- already recorded by this stage

        pendingMovement = result;
        phase = GamePhase.SPRING_RETREAT;

    }

    private void resolveFallMovement(MovementResult result) {

        /*
         * Make the post-movement board visible during any Fall retreat phase,
         * but do not update SC ownership yet: a retreat can still occupy and
         * capture a supply center.
         */
        board = board.after(result);

        if (!result.dislodgements().isEmpty()) {
            pendingMovement = result;
            phase = GamePhase.FALL_RETREAT;
            return;
        }

        /*
         * AdjustmentInput requires a RetreatResult. With no dislodgements,
         * processing an empty retreat list produces the equivalent final Fall
         * result without exposing a pointless retreat phase.
         */
        latestFallRetreat = retreatProcessor.process(
                new RetreatInput(result, List.of()));

        processorHistory.add(retreatProcessor);  // add to history (ps references list)

        board = board.afterFallTurn(latestFallRetreat);
        phase = GamePhase.WINTER_ADJUSTMENT;

    }

    private void requireMovementPhase() {
        if (!phase.isMovement()) {
            throw new IllegalStateException(
                    "Cannot resolve movement during " + phase);
        }
    }

    private void requireRetreatPhase() {
        if (!phase.isRetreat()) {
            throw new IllegalStateException(
                    "Cannot resolve retreats during " + phase);
        }
    }

    // small helper
    private static <T> List<T> immutableList(Collection<T> supplied, String argumentName) {
        return List.copyOf(
                Objects.requireNonNull(supplied, argumentName));
    }

}