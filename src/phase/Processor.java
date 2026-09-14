package phase;

/**
 * Input state + submitted orders ==> 'result'
 */
public interface Processor<I, R> {

    R process(I input);

}