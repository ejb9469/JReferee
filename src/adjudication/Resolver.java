package adjudication;

/**
 * Performs an Orders analysis without mutation (unlike `Adjudicator`).
 * Analyzes what can be proven without committing a game result,
 * producing an `OrdinaryResolution` record (immutable).
 */
public interface Resolver {

    OrdinaryResolution inquire();

}
