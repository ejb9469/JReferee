package game;


import domain.Nation;
import domain.Province;
import phase.BoardRules;
import phase.PhaseResult;
import phase.UnitId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


// immutable
public final class BoardState {


    private final Map<UnitId, Province> unitLocations;
    private final Map<Province, Nation> scOwners;

    // Processors are stored at the `Game` level, in a List


    public BoardState(Map<UnitId, Province> unitLocations,
                      Map<Province, Nation> scOwners) {

        this.unitLocations = BoardRules.unitLocations(unitLocations, "unitLocations");
        this.scOwners = BoardRules.scOwners(scOwners, "scOwners");

    }


    // Basic getters
    public Map<UnitId, Province> locations() {
        return unitLocations;
    }

    public Map<Province, Nation> owners() {
        return scOwners;
    }

    public Province locationOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return unitLocations.get(unit);
    }

    public Nation ownerOf(Province province) {
        Objects.requireNonNull(province, "province");
        return ( scOwners.get(Province.canonical(province)) );
    }


    // Functions
    /**
     * Applies a completed phase result without changing supply-center control.
     *
     * <p>Use this after Spring movement, Spring retreats, and Winter
     * adjustments.</p>
     */
    public BoardState after(PhaseResult result) {

        Objects.requireNonNull(result, "result");

        return new BoardState(
                result.finalLocations(),
                this.scOwners);

    }

    /**
     * Applies the final result of a Fall turn and updates supply-center
     * ownership.
     *
     * <p>Every supply center occupied at the end of the Fall turn becomes
     * controlled by the occupying unit's nation. This method must receive the
     * Fall movement result only when there were no dislodgements; otherwise it
     * must receive the subsequent Fall retreat result.</p>
     *
     * <p>Vacant supply centers retain their prior owner.</p>
     */
    public BoardState afterFallTurn(PhaseResult result) {

        Objects.requireNonNull(result, "result");

        Map<Province, Nation> nextOwners = new LinkedHashMap<>(this.scOwners);

        for (Map.Entry<UnitId, Province> entry : result.finalLocations().entrySet()) {
            Province territory = Province.canonical(entry.getValue());
            if (territory.supplyCenter)
                nextOwners.put(territory,
                        entry.getKey().owner());
        }

        return new BoardState(
                result.finalLocations(),
                nextOwners);

    }


}
