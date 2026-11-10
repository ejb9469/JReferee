package agents.execution;

import agents.generation.ActionCandidate;
import agents.generation.ActionDistribution;
import agents.search.SearchResult;
import domain.Nation;
import game.GameState;

import java.util.*;

/**
 * One seat's candidate pools, returned policy, and sampled action.
 *
 * <p>Pools belong to this seat's root search. Opponent pools can differ from
 * those used by another seat. Rollout pools are not retained here.</p>
 *
 * <p>The search result contains the deciding seat's historical payoff means.
 * Prior sampling has no search result and retains only its own pool.
 * Elapsed time covers generation, search, and sampling, excluding reporting
 * and real adjudication. This diagnostic is separate from the GameRecord.</p>
 */
public record AgentDecision(
        GameState state,
        AgentSettings settings,
        long searchSeed,
        long rolloutSeed,
        long selectionSeed,
        long elapsedNanos,
        Map<Nation, List<ActionCandidate>> pools,
        ActionDistribution distribution,
        Optional<SearchResult> searchResult,
        int selectedIndex
) {

    public AgentDecision {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(searchResult, "searchResult");
        if (elapsedNanos < 0)
            throw new IllegalArgumentException("Elapsed time must not be negative");
        if (!state.aliveNations().contains(distribution.power()))
            throw new IllegalArgumentException("Decision belongs to an eliminated nation");
        if (selectedIndex < 0 || selectedIndex >= distribution.candidates().size()
                || distribution.probabilities().get(selectedIndex) <= 0)
            throw new IllegalArgumentException("Selected candidate must have positive policy probability");

        Map<Nation, List<ActionCandidate>> checked = new EnumMap<>(Nation.class);
        Objects.requireNonNull(pools, "pools").forEach((nation, candidates) -> {
            List<ActionCandidate> copy = List.copyOf(candidates);
            if (copy.isEmpty() || copy.size() > settings.candidateLimit()
                    || copy.stream().anyMatch(candidate -> candidate.power() != nation))
                throw new IllegalArgumentException("Invalid decision pool for " + nation);
            checked.put(nation, copy);
        });
        Set<Nation> expected = settings.method() == AgentSettings.Method.PRIOR
                ? Set.of(distribution.power()) : state.aliveNations();
        if (!checked.keySet().equals(expected)
                || !distribution.candidates().equals(checked.get(distribution.power())))
            throw new IllegalArgumentException("Decision pools do not match the root and returned policy");
        pools = Collections.unmodifiableMap(checked);

        if (searchResult.isPresent() != (settings.method() != AgentSettings.Method.PRIOR))
            throw new IllegalArgumentException("Search result does not match the agent method");
        if (searchResult.isPresent() && !searchResult.orElseThrow().distribution().equals(distribution))
            throw new IllegalArgumentException("Search and decision policies differ");
    }

    public Nation power() {
        return distribution.power();
    }

    public ActionCandidate selected() {
        return distribution.candidates().get(selectedIndex);
    }

}
