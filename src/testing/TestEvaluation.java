package testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static testing.AdjudicatorTestCase.TESTCASE_PREFIX;

/**
 * Immutable collection of observable checks made by a processor test case.
 *
 * <p>Each check has a useful name and can compare arbitrary phase-level
 * values: retreat statuses, destroyed-unit sets, final locations, adjustment
 * statuses, built-unit counts, and civil-disorder disbands.</p>
 */
public final class TestEvaluation {

    private final List<Check> checks;

    public TestEvaluation(List<Check> checks) {
        this.checks = List.copyOf(
                Objects.requireNonNull(checks, "checks")
        );
    }

    public List<Check> checks() {
        return checks;
    }

    public int score() {

        int score = 0;

        for (Check check : checks)
            if (check.passed())
                score++;

        return score;

    }

    public int size() {
        return checks.size();
    }

    public boolean passed() {
        return score() == size();
    }

    public String format(String name) {

        StringBuilder output = new StringBuilder(
                TESTCASE_PREFIX
                        + name
                        + ":\n\n"
        );

        for (Check check : checks) {
            output.append(check.label())
                    .append("\n\tEXPECTED: ")
                    .append(check.expected())
                    .append("\n\tACTUAL:   ")
                    .append(check.actual())
                    .append("\n\tRESULT:   ")
                    .append(check.passed() ? "PASS" : "FAIL")
                    .append("\n");
        }

        output.append("\nSCORE: ")
                .append(score())
                .append("/")
                .append(size());

        return output.toString();

    }

    public record Check(
            String label,
            Object expected,
            Object actual
    ) {

        public Check {
            Objects.requireNonNull(label, "label");
        }

        public boolean passed() {
            return Objects.equals(expected, actual);
        }

    }

    public static final class Builder {

        private final List<Check> checks = new ArrayList<>();

        public Builder expect(
                String label,
                Object expected,
                Object actual
        ) {
            checks.add(new Check(label, expected, actual));
            return this;
        }

        public TestEvaluation build() {
            return new TestEvaluation(checks);
        }

    }

}