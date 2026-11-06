package misc.scoring;

import domain.Nation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * Final-center scoring rules for standard seven-power, 34-center Diplomacy.
 *
 * <p>The caller determines when the game has ended and supplies its recorded
 * center counts. Eighteen or more centers awards a solo. Otherwise, these
 * variants score a draw or an agreed cutoff. Draw exclusion, concessions,
 * and elimination-year tie breaks require additional outcome information.</p>
 *
 * <p>Rules follow the documented DSS, SoS, C-Diplo 100, OMG, and Duct Tape V2
 * variants. In Duct Tape V2, eliminated nations tie for the remaining ranks.
 * All seven nations must be supplied, including zeros.</p>
 */
public enum ScoringSystem {

    /** All nations with centers share 100 points equally; a solo receives 100. */
    DRAW_SIZE(100.0),

    /** Draw points are 100 * centers squared / sum of squares; a solo receives 100. */
    SUM_OF_SQUARES(100.0),

    /** One participation point, one per center, and rank bonuses 38/14/7; a solo receives 100. */
    C_DIPLO_100(100.0),

    /**
     * 1.5 per center, 9 for survival, and rank bonuses 4.5/3/1.5.
     * A sole leader collects the first-to-second center gap from each other
     * nation, capped at half that nation's preliminary score. A solo receives 100.
     */
    OPEN_MIND_THE_GAP(100.0),

    /**
     * One per center and rank bonuses 40/24/15/9/6/3/3; a solo receives 75.
     * All ties, including eliminations, split their occupied rank bonuses.
     */
    DUCT_TAPE_V2(75.0);

    private static final int TOTAL_CENTERS = 34;
    private static final int SOLO_CENTERS = 18;

    private final double soloScore;

    ScoringSystem(double soloScore) {
        this.soloScore = soloScore;
    }

    public double soloScore() {
        return soloScore;
    }

    /**
     * Returns immutable native tournament points in nation order.
     * Rank ties share the bonuses for all positions occupied by the tie.
     *
     * @param   centerCounts final counts for all seven nations
     * @throws  NullPointerException if the map, a key, or a count is null
     * @throws  IllegalArgumentException if nations are missing, a count is
     *          outside 0..34, or the total is outside 1..34
     */
    public Map<Nation, Double> score(Map<Nation, Integer> centerCounts) {

        Map<Nation, Integer> counts = validatedCounts(centerCounts);
        Map<Nation, Double> scores = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            scores.put(nation, 0.0);

        for (Nation nation : Nation.values())
            if (counts.get(nation) >= SOLO_CENTERS) {
                scores.put(nation, soloScore);
                return Collections.unmodifiableMap(scores);
            }

        switch (this) {
            case DRAW_SIZE -> {
                long survivors = counts.values().stream().filter(count -> count > 0).count();
                for (Nation nation : Nation.values())
                    if (counts.get(nation) > 0)
                        scores.put(nation, 100.0 / survivors);
            }
            case SUM_OF_SQUARES -> {
                int squares = counts.values().stream().mapToInt(count -> count * count).sum();
                for (Nation nation : Nation.values()) {
                    int count = counts.get(nation);
                    scores.put(nation, 100.0 * count * count / squares);
                }
            }
            case C_DIPLO_100 -> {
                scores.putAll(rankBonuses(counts, 38.0, 14.0, 7.0));
                scores.replaceAll((nation, bonus) -> 1.0 + counts.get(nation) + bonus);
            }
            case OPEN_MIND_THE_GAP -> {
                scores.putAll(rankBonuses(counts, 4.5, 3.0, 1.5));
                scores.replaceAll((nation, bonus) ->
                        1.5 * counts.get(nation) + (counts.get(nation) > 0 ? 9.0 : 0.0) + bonus);
                payGap(counts, scores);
            }
            case DUCT_TAPE_V2 -> {
                scores.putAll(rankBonuses(counts, 40.0, 24.0, 15.0, 9.0, 6.0, 3.0, 3.0));
                scores.replaceAll((nation, bonus) -> counts.get(nation) + bonus);
            }
        }

        return Collections.unmodifiableMap(scores);

    }

    /**
     * Divides native points by this system's fixed solo award.
     *
     * <p>SoS and DSS produce a vector summing to one, with a solo scored 1/0.
     * This is the SoS reward scale used by Diplodocus. Other systems retain
     * their variable total payouts. Renormalizing each result to sum to one
     * would change their incentives.</p>
     */
    public Map<Nation, Double> rewards(Map<Nation, Integer> centerCounts) {
        Map<Nation, Double> rewards = new EnumMap<>(score(centerCounts));
        rewards.replaceAll((nation, points) -> points / soloScore);
        return Collections.unmodifiableMap(rewards);
    }

    private static Map<Nation, Integer> validatedCounts(Map<Nation, Integer> centerCounts) {
        Map<Nation, Integer> counts = Map.copyOf(Objects.requireNonNull(centerCounts, "centerCounts"));
        if (!counts.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalArgumentException("Scoring requires a center count for every nation");

        int total = 0;
        for (Nation nation : Nation.values()) {
            int count = counts.get(nation);
            if (count < 0 || count > TOTAL_CENTERS)
                throw new IllegalArgumentException("Invalid center count for " + nation + ": " + count);
            total += count;
        }
        if (total == 0 || total > TOTAL_CENTERS)
            throw new IllegalArgumentException("Total owned centers must be between 1 and " + TOTAL_CENTERS);
        return counts;
    }

    private static Map<Nation, Double> rankBonuses(Map<Nation, Integer> counts, double... bonuses) {
        Map<Nation, Double> result = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values()) {
            int higher = 0;
            int tied = 0;
            for (int count : counts.values()) {
                if (count > counts.get(nation))
                    higher++;
                else if (count == counts.get(nation))
                    tied++;
            }
            double pool = 0.0;
            for (int rank = higher; rank < Math.min(higher + tied, bonuses.length); rank++)
                pool += bonuses[rank];
            result.put(nation, pool / tied);
        }
        return result;
    }

    private static void payGap(Map<Nation, Integer> counts, Map<Nation, Double> scores) {
        Nation leader = Nation.AUSTRIA;
        for (Nation nation : Nation.values())
            if (counts.get(nation) > counts.get(leader))
                leader = nation;

        int second = 0;
        for (Nation nation : Nation.values())
            if (nation != leader)
                second = Math.max(second, counts.get(nation));
        int gap = counts.get(leader) - second;
        if (gap == 0)
            return;

        double collected = 0.0;
        for (Nation nation : Nation.values()) {
            if (nation == leader)
                continue;
            double payment = Math.min(gap, scores.get(nation) / 2.0);
            scores.put(nation, scores.get(nation) - payment);
            collected += payment;
        }
        scores.put(leader, scores.get(leader) + collected);
    }

}