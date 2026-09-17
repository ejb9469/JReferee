package parsing;

import testing.AdjudicatorTestCase;

import java.util.Collection;

/**
 * Interface describing the functionality of a test case parser using file(s) as input
 */
public interface FileTestCaseParser extends TestCaseParser {

    @Override
    public AdjudicatorTestCase parse(String source);

    public Collection<AdjudicatorTestCase> parseManyFiles();

}
