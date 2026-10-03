package analysis;

import game.BoardState;
import phase.Order;
import java.util.*;

/** Advisory principles never reject plans or alter numerical ranking. */
public interface TacticalPrinciple {
    List<Warning> inspect(BoardState board, OrderPlan plan);

    enum Category { FRIENDLY_CONVOY_FLEET }
    enum Severity { WARNING }
    enum EvaluationStatus { NOT_REQUESTED, UNAVAILABLE, INVALID, LIMIT_REACHED, EVALUATED }

    record Warning(String id, Category category, Severity severity, List<Order> orders,
                   String explanation, Order suggestedAlternative,
                   EvaluationStatus evaluatedStatus) {
        public Warning {
            Objects.requireNonNull(id);
            Objects.requireNonNull(category);
            Objects.requireNonNull(severity);
            orders = List.copyOf(orders);
            Objects.requireNonNull(explanation);
            Objects.requireNonNull(evaluatedStatus);
        }
        public boolean rejected() { return false; }
        public double penalty() { return 0; }
        public String principleId() {
            return switch (category) {
                case FRIENDLY_CONVOY_FLEET -> "friendly-convoy-fleet";
            };
        }
    }
}
