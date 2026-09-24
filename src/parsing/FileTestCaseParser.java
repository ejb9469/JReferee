package parsing;

import testing.AdjudicatorTestCase;
import testing.TestCase;

import java.util.Collection;

/**
 * Interface describing the functionality of a test case parser using file(s) as input
 */
public interface FileTestCaseParser extends TestCaseParser {

    @Override
    public TestCase parse(String source);

    @Deprecated
    public Collection<AdjudicatorTestCase> parseManyFiles();

}
