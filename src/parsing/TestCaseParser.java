package parsing;

import testing.TestCase;

/**
 * Interface describing the functionality of a test case parser (from String -> `TestCase`)
 */
public interface TestCaseParser {

    public TestCase parse(String source);

}