package parsing;

import testing.AdjudicatorTestCase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
  * Responsible for parsing Test Case files using the Order syntax found at the
 * <a href="https://webdiplomacy.net/doc/DATC_v3_0.html">WebDip DATC page</a>
 */
public class DATCFileParser extends DATCParser implements FileTestCaseParser {

    public static final String TESTGAMES_DIR_PATH = "src/resources/testgames/";
    public static final String TESTGAMES_SOLUTIONS_DIR_PATH = "src/resources/testgames_solutions/";
    public static final String TESTGAMES_FILE_EXT = "";  // Include the dot (unless an empty String)

    public final String dirPath;
    public final String solutionsDirPath;


    public DATCFileParser() {
        super();
        this.dirPath = TESTGAMES_DIR_PATH;
        this.solutionsDirPath = TESTGAMES_SOLUTIONS_DIR_PATH;
    }


    @Override
    public AdjudicatorTestCase parse(String path) {

        if (path.isBlank() || this.dirPath.isBlank())
            return null;

        Path fullPath;
        try {
            fullPath = Paths.get(this.dirPath, path);
        } catch (InvalidPathException ex) {
            //ex.printStackTrace();
            System.err.printf("`%s`: Tried to read test case from %s, file not found\n", "DATCFileParser.java", this.dirPath+"/"+path);
            return null;
        }

        String contents;
        try {
            contents = Files.readString(Paths.get(fullPath.toUri()));
        }
        catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }

        Path solutionFullPath;
        try {
            solutionFullPath = Paths.get(this.solutionsDirPath, path);
        } catch (InvalidPathException ex) {
            //ex.printStackTrace();
            System.err.printf("`%s`: Tried to read solution from %s, file not found\n", "DATCFileParser.java", this.solutionsDirPath+"/"+path);
            // Continue with execution
            solutionFullPath = null;
        }

        if (solutionFullPath == null)
            return super.parse(contents);
        else {

            String solutionContents;
            try {
                solutionContents = Files.readString(Paths.get(solutionFullPath.toUri()));
            } catch (IOException ex) {
                //ex.printStackTrace();
                return super.parse(contents);
            }

            AdjudicatorTestCase testCase = super.parse(contents);
            testCase.setName(path.substring(0, (path.length() - TESTGAMES_FILE_EXT.length()) ));
            testCase.setExpectedFields(this.parseSolution(solutionContents));
            return testCase;

        }

    }

    private boolean[] parseSolution(String solution) {

        // Current version of this method works for single-boolean (verdict) only

        String[] sourceLines = solution.lines().toArray(String[]::new);
        int numGarbageLines = 0;
        for (String line : sourceLines) {
            if (line.isBlank())
                numGarbageLines++;
        }

        boolean[] verdicts = new boolean[sourceLines.length - numGarbageLines];
        int encounteredGarbageLines = 0;
        for (int i = 0; i < sourceLines.length; i++) {
            String line = sourceLines[i];
            if (line.isBlank()) {
                encounteredGarbageLines++;
                continue;
            }
            // Warning: `Boolean.parseBoolean()` may return null (but will not throw an exception)
            verdicts[i - encounteredGarbageLines] = Boolean.parseBoolean(line.strip());
        }

        return verdicts;

    }

    public Collection<AdjudicatorTestCase> parseManyFiles() {

        if (this.dirPath.isBlank())
            return null;

        Path fullPath;
        try {
            fullPath = Paths.get(this.dirPath);
        } catch (InvalidPathException ex) {
            //ex.printStackTrace();
            System.err.printf("`%s`: Tried to read test cases from %s, file(s) not found\n", "DATCFileParser.java", this.dirPath);
            return null;
        }

        File testCasesDir = new File(fullPath.toUri());
        File[] testCaseFiles = testCasesDir.listFiles();

        if (testCaseFiles == null)
            return null;

        List<AdjudicatorTestCase> testCases = new ArrayList<>();
        for (File testCaseFile : testCaseFiles) {
            System.out.println("Reading from...\t\t["+testCaseFile.getPath()+"]");
            testCases.add(this.parse(testCaseFile.getName()));
        }

        return testCases;

    }

}
