package parsing.diplobn;

import domain.Nation;
import domain.Province;

import java.util.Objects;


/**
 * The optional DiploBN result annotation attached to a submitted order.
 *
 * <p>This is historical source data. It is deliberately separate from
 * JReferee's own processor and adjudicator outcomes.</p>
 */
public record DiploBNOrderResolution(
        Nation nation,
        Province issuingProvince,
        boolean retreatOrder,
        Boolean successful,
        String reason
) {

    public DiploBNOrderResolution {

        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(issuingProvince, "issuingProvince");

        if (successful == null && reason != null)
            throw new IllegalArgumentException(
                    "A resolution reason requires a success/failure result");

    }

}