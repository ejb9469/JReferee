package parsing;

import testing.AdjudicatorTestCase;

/**
 * Interface describing the functionality of a test case parser (from String -> `TestCase`)
 */
public interface TestCaseParser {

    public AdjudicatorTestCase parse(String source);

}