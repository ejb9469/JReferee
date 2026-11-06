package agents.generation;

import contracts.OrderForm;
import phase.Order;
import phase.UnitId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Generates movement or retreat candidates using an independent-uniform policy.
 *
 * <p>The first action is all-hold in movement, or all-omitted in retreats.
 * Omission destroys a dislodged unit. Further actions are sampled without keeping
 * duplicates. Each candidate's logPrior is its probability under the full
 * catalog product, expressed as a natural logarithm.</p>
 *
 * <p>Units are ordered by current province; options by type and targets.
 * Equivalent states and the same seed therefore produce the same candidates
 * regardless of map or catalog iteration order.</p>
 *
 * <p>The target count is the smaller of candidateLimit and the action-space
 * size. At most 32 samples per target candidate are drawn. The returned list
 * may be shorter if duplicates exhaust that budget.</p>
 */
public final class UniformActionGenerator implements ActionGenerator {

    private static final int SAMPLES_PER_CANDIDATE = 32;

    private static final Comparator<OrderForm> ORDER_COMPARATOR =
            Comparator.comparing(OrderForm::orderType)
                    .thenComparing(OrderForm::target,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(OrderForm::auxiliaryTarget,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    @Override
    public List<ActionCandidate> generate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(request.state(), request.power());

        List<UnitId> units = new ArrayList<>(catalog.optionsByUnit().keySet());
        units.sort(Comparator.comparing(catalog.unitLocations()::get));

        List<List<OrderForm>> optionsByUnit = new ArrayList<>();
        List<OrderForm> defaults = new ArrayList<>();
        int omissionChoices = catalog.allowsOmission() ? 1 : 0;
        double logPrior = 0.0;
        int targetSize = 1;

        for (UnitId unit : units) {
            List<OrderForm> options = new ArrayList<>(catalog.optionsFor(unit));
            options.sort(ORDER_COMPARATOR);
            optionsByUnit.add(options);
            if (!catalog.allowsOmission())
                defaults.add(Order.hold(unit));
            int choiceCount = options.size() + omissionChoices;
            logPrior -= StrictMath.log(choiceCount);

            targetSize = (int) Math.min(
                    request.candidateLimit(), (long) targetSize * choiceCount);
        }

        List<ActionCandidate> candidates = new ArrayList<>();
        candidates.add(checkedCandidate(
                request, catalog, defaults, logPrior, ActionCandidate.Source.HEURISTIC));

        Set<List<OrderForm>> seen = new HashSet<>();
        seen.add(List.copyOf(defaults));

        Random random = new Random(request.seed());
        long sampleLimit = (long) SAMPLES_PER_CANDIDATE * targetSize;

        for (long sample = 0;
             sample < sampleLimit && candidates.size() < targetSize;
             sample++) {
            List<OrderForm> orders = new ArrayList<>(units.size());
            for (List<OrderForm> options : optionsByUnit) {
                int choice = random.nextInt(options.size() + omissionChoices);
                if (choice < options.size())
                    orders.add(options.get(choice));
            }

            List<OrderForm> action = List.copyOf(orders);
            if (seen.add(action))
                candidates.add(checkedCandidate(
                        request, catalog, action, logPrior,
                        ActionCandidate.Source.POLICY_SAMPLE));
        }

        return List.copyOf(candidates);
    }

    private static ActionCandidate checkedCandidate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog,
            List<OrderForm> orders,
            double logPrior,
            ActionCandidate.Source source
    ) {
        ActionCandidate candidate = new ActionCandidate(
                request.power(), orders, logPrior, source);
        List<ActionValidationError> errors = new CatalogActionValidator()
                .validate(request.state(), candidate, catalog);

        if (!errors.isEmpty())
            throw new IllegalStateException(
                    "Generated an invalid action: " + errors);

        return candidate;
    }

}