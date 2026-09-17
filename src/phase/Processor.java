package phase;

/**
 * Input state + submitted orders ==> 'result'
 */
public interface Processor<I extends PhaseInput, R extends PhaseResult> {

    R process(I input);

}