package phase.adjustments;

import contracts.OrderForm;
import domain.Nation;

/**
 * Immutable player-submitted winter adjustment order.
 *
 * <p>Builds, disbands, and waives are structurally different orders at the `phase` level,
 * not simply a change in fields of a singular `Order`, like in `adjudication`.</p>
 */
public sealed interface AdjustmentOrder
        extends OrderForm
        permits BuildOrder, DisbandOrder, WaiveOrder
{   }