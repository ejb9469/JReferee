package testing;

import phase.PhaseInput;
import phase.PhaseResult;
import phase.Processor;

import java.util.Objects;

import static testing.AdjudicatorTestCase.TESTCASE_PREFIX;

/**
 * Base test case for an immutable phase processor.
 *
 * <p>This class is intentionally separate from the legacy adjudication-order
 * harness. It executes a phase input through a processor and verifies the
 * immutable phase result.</p>
 *
 * @param <I> phase-specific immutable input
 * @param <R> phase-specific immutable result
 */
public abstract class ProcessorTestCase<I extends PhaseInput, R extends PhaseResult>
                        implements TestCase {

    protected final String name;
    protected final Processor<I, R> processor;
    protected final I input;

    private TestEvaluation evaluation;
    private String eval;


    protected ProcessorTestCase(
            String name,
            Processor<I, R> processor,
            I input
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.input = Objects.requireNonNull(input, "input");
    }


    /**
     * Produces labeled checks from the completed immutable phase result.
     */
    protected abstract TestEvaluation verify(R result);


    @Override
    public final void eval(boolean print) {

        R result = processor.process(input);

        this.evaluation = Objects.requireNonNull(
                verify(result),
                "verify(...) returned null");

        this.eval = evaluation.format(name);

        if (print)
            printEval();

    }


    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getEval() {
        return eval;
    }

    @Override
    public final int getScore() {
        return evaluation == null
                ? 0
                : evaluation.score();
    }

    @Override
    public final int getSize() {
        return evaluation == null
                ? 0
                : evaluation.size();
    }

    public final TestEvaluation getEvaluation() {
        return evaluation;
    }


    @Override
    public final void printEval() {
        if (eval == null) {
            System.out.println(
                    "UNEVALUATED " + TESTCASE_PREFIX + name);
            return;
        }
        System.out.println(eval + "\n\n");
    }

    @Override
    public final void printNameAndScore() {
        System.out.printf(
                "[%02d/%02d]\t%s%s%n",
                getScore(),
                getSize(),
                TESTCASE_PREFIX,
                getName());
    }

    @Override
    public final String toString() {
        return name;
    }

}