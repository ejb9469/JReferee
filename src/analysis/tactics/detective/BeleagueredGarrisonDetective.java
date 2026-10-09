package analysis.tactics.detective;

import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;

import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Recognizes competing supported moves into an occupied territory.<br><br>
 *
 * A match requires an initial occupant and at least two distinct incoming
 * movers, each with at least one matching known support-to-move order.
 *
 * <p>Unsupported moves do not contribute to the threshold and are not
 * included as attackers. Multiple supporters for one move still represent
 * only one incoming attack.</p>
 *
 * <p>Support matching is structural and uses canonical territories.
 * It does not establish geographic legality, effective support, relative
 * strength, defender survival, or a realized beleaguered garrison.</p>
 */
public final class BeleagueredGarrisonDetective extends Detective {


    // Constants \\

    /*
     * v1 counted unsupported incoming moves.
     * v2 requires separate supported attacks and includes their supporters.
     */
    private static final String VERSION = "structure-v2";


    // Construction \\

    public BeleagueredGarrisonDetective() {
        super(VERSION);
    }


    // Detective identity \\

    @Override
    public TacticKind kind() {
        return TacticKind.BELEAGUERED_GARRISON;
    }


    // Examination \\

    @Override
    protected List<TacticMatch> examine(TacticalContext context) {

        Map<Province, List<SupportedAttack>> incoming =
                new EnumMap<>(Province.class);

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order move = evidence.order();

            if (move.orderType() != OrderType.MOVE)
                continue;

            Province destination = Province.canonical(move.target());

            Optional<UnitId> occupant = context.unitAt(destination);

            if (occupant.isEmpty())
                continue;

            // The occupant's self-move is not another incoming attack.
            if (occupant.get().equals(move.unit()))
                continue;

            List<UnitId> supporters = supportersOf(context, move);

            // An unsupported move never qualifies for this pattern.
            if (supporters.isEmpty())
                continue;

            incoming.computeIfAbsent(
                    destination, ignored -> new ArrayList<>()
            ).add(new SupportedAttack(move.unit(), supporters));

        }

        List<TacticMatch> findings = new ArrayList<>();

        for (Map.Entry<Province, List<SupportedAttack>> entry
                : incoming.entrySet()) {

            List<SupportedAttack> attacks = entry.getValue();

            /*
             * TacticalContext permits at most one known order per unit.
             * Each entry therefore represents a distinct incoming mover,
             * regardless of how many supporters that move has.
             */
            if (attacks.size() < 2)
                continue;

            Province destination = entry.getKey();
            UnitId defender = context.unitAt(destination).orElseThrow();

            Set<TacticMatch.Participant> participants = new LinkedHashSet<>();

            participants.add(new TacticMatch.Participant(
                    TacticMatch.Role.DEFENDER,
                    defender));

            for (SupportedAttack attack : attacks) {

                participants.add(new TacticMatch.Participant(
                        TacticMatch.Role.ATTACKER,
                        attack.attacker()));

                for (UnitId supporter : attack.supporters())
                    participants.add(new TacticMatch.Participant(
                            TacticMatch.Role.SUPPORTER,
                            supporter));

            }

            /*
             * All qualifying moves and support orders are known.
             * The defender's order and surrounding interference are not
             * prerequisites for this structural candidate.
             */
            findings.add(new TacticMatch(
                    kind(),
                    version(),
                    context,
                    destination,
                    List.copyOf(participants),
                    Set.of()));

        }

        return findings;

    }


    // Support matching \\

    /**
     * Finds known support-to-move orders matching this mover and destination.
     *
     * <p>Uses the mover's current board location, never its creation origin.
     * Exact coast-bearing submissions remain unchanged in the context.</p>
     */
    private static List<UnitId> supportersOf(
            TacticalContext context,
            Order move
    ) {

        Province origin = Province.canonical(
                context.board().locationOf(move.unit()));

        Province destination = Province.canonical(move.target());

        List<UnitId> supporters = new ArrayList<>();

        for (TacticalContext.KnownOrder evidence : context.orders().values()) {

            Order support = evidence.order();

            if (support.orderType() != OrderType.SUPPORT
                    || support.auxiliaryTarget() == null)
                continue;

            if (support.unit().equals(move.unit()))
                continue;

            if (Province.canonical(support.target()) != origin)
                continue;

            if (Province.canonical(support.auxiliaryTarget()) != destination)
                continue;

            supporters.add(support.unit());

        }

        return List.copyOf(supporters);

    }


    // Qualifying attacks \\

    private record SupportedAttack(
            UnitId attacker,
            List<UnitId> supporters
    ) {

        private SupportedAttack {

            Objects.requireNonNull(attacker, "attacker");

            supporters = List.copyOf(
                    Objects.requireNonNull(supporters, "supporters"));

            if (supporters.isEmpty())
                throw new IllegalArgumentException(
                        "A supported attack requires at least one supporter");

        }

    }


}