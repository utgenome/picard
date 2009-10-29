/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.sf.picard.cmdline;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;

public class CommandLineParserTest {
    enum FrobnicationFlavor {
        FOO, BAR, BAZ
    }

    class FrobnicateOptions {
        @Usage(programVersion="1.0")
        public static final String USAGE = "Usage: frobnicate [options] input-file output-file\n\nRead input-file, frobnicate it, and write frobnicated results to output-file\n";

        @PositionalArguments(minElements=2, maxElements=2)
        public List<File> positionalArguments = new ArrayList<File>();

        @Option(shortName="T", doc="Frobnication threshold setting.")
        public Integer FROBNICATION_THRESHOLD = 20;

        @Option
        public FrobnicationFlavor FROBNICATION_FLAVOR;

        @Option(doc="Allowed shmiggle types.", minElements=1, maxElements = 3)
        public List<String> SHMIGGLE_TYPE = new ArrayList<String>();

        @Option
        public Boolean TRUTHINESS;
    }

    class OptionsWithoutPositional {
        @Usage
        public static final String USAGE = "Usage: framistat [options]\n\nCompute the plebnick of the freebozzle.\n";
        @Option(shortName="T", doc="Frobnication threshold setting.")
        public Integer FROBNICATION_THRESHOLD = 20;

        @Option
        public FrobnicationFlavor FROBNICATION_FLAVOR;

        @Option(doc="Allowed shmiggle types.", minElements=1, maxElements = 3)
        public List<String> SHMIGGLE_TYPE = new ArrayList<String>();

        @Option
        public Boolean TRUTHINESS;
    }

    class OptionsWithCaseClash {
        @Option
        public String FROB;
        @Option
        public String frob;
    }
    
    class MutextOptions {
        @Option(mutex={"M", "N", "Y", "Z"})
        public String A;
        @Option(mutex={"M", "N", "Y", "Z"})
        public String B;
        @Option(mutex={"A", "B", "Y", "Z"})
        public String M;
        @Option(mutex={"A", "B", "Y", "Z"})
        public String N;
        @Option(mutex={"A", "B", "M", "N"})
        public String Y;
        @Option(mutex={"A", "B", "M", "N"})
        public String Z;
        
    }

    @Test
    public void testUsage() {
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        clp.usage(System.out);
    }

    @Test
    public void testUsageWithoutPositional() {
        final OptionsWithoutPositional fo = new OptionsWithoutPositional();
        final CommandLineParser clp = new CommandLineParser(fo);
        clp.usage(System.out);
    }

    @Test
    public void testPositive() {
        final String[] args = {
                "T=17",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.positionalArguments.size(), 2);
        final File[] expectedPositionalArguments = { new File("positional1"), new File("positional2")};
        Assert.assertEquals(fo.positionalArguments.toArray(), expectedPositionalArguments);
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 17);
        Assert.assertEquals(fo.FROBNICATION_FLAVOR, FrobnicationFlavor.BAR);
        Assert.assertEquals(fo.SHMIGGLE_TYPE.size(), 2);
        final String[] expectedShmiggleTypes = {"shmiggle1", "shmiggle2"};
        Assert.assertEquals(fo.SHMIGGLE_TYPE.toArray(), expectedShmiggleTypes);
        Assert.assertFalse(fo.TRUTHINESS);
    }

    /**
     * Allow a whitespace btw equal sign and option value.
     */
    @Test
    public void testPositiveWithSpaces() {
        final String[] args = {
                "T=", "17",
                "FROBNICATION_FLAVOR=", "BAR",
                "TRUTHINESS=", "False",
                "SHMIGGLE_TYPE=", "shmiggle1",
                "SHMIGGLE_TYPE=", "shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.positionalArguments.size(), 2);
        final File[] expectedPositionalArguments = { new File("positional1"), new File("positional2")};
        Assert.assertEquals(fo.positionalArguments.toArray(), expectedPositionalArguments);
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 17);
        Assert.assertEquals(fo.FROBNICATION_FLAVOR, FrobnicationFlavor.BAR);
        Assert.assertEquals(fo.SHMIGGLE_TYPE.size(), 2);
        final String[] expectedShmiggleTypes = {"shmiggle1", "shmiggle2"};
        Assert.assertEquals(fo.SHMIGGLE_TYPE.toArray(), expectedShmiggleTypes);
        Assert.assertFalse(fo.TRUTHINESS);
    }

    @Test
    public void testPositiveWithoutPositional() {
        final String[] args = {
                "T=17",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
        };
        final OptionsWithoutPositional fo = new OptionsWithoutPositional();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 17);
        Assert.assertEquals(fo.FROBNICATION_FLAVOR, FrobnicationFlavor.BAR);
        Assert.assertEquals(fo.SHMIGGLE_TYPE.size(), 2);
        final String[] expectedShmiggleTypes = {"shmiggle1", "shmiggle2"};
        Assert.assertEquals(fo.SHMIGGLE_TYPE.toArray(), expectedShmiggleTypes);
        Assert.assertFalse(fo.TRUTHINESS);
    }

    /**
     * If last character of command line is the equal sign in an option=value pair,
     * make sure no crash, and that the value is empty string.
     */
    @Test
    public void testPositiveTerminalEqualSign() {
        final String[] args = {
                "T=17",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=",
        };
        final OptionsWithoutPositional fo = new OptionsWithoutPositional();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 17);
        Assert.assertEquals(fo.FROBNICATION_FLAVOR, FrobnicationFlavor.BAR);
        Assert.assertEquals(fo.SHMIGGLE_TYPE.size(), 2);
        final String[] expectedShmiggleTypes = {"shmiggle1", ""};
        Assert.assertEquals(fo.SHMIGGLE_TYPE.toArray(), expectedShmiggleTypes);
        Assert.assertFalse(fo.TRUTHINESS);
    }

    @Test
    public void testDefault() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 20);
    }

    @Test
    public void testMissingRequiredArgument() {
        final String[] args = {
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testBadValue() {
        final String[] args = {
                "FROBNICATION_THRESHOLD=ABC",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testBadEnumValue() {
        final String[] args = {
                "FROBNICATION_FLAVOR=HiMom",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testNotEnoughOfListOption() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testTooManyListOption() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "SHMIGGLE_TYPE=shmiggle3",
                "SHMIGGLE_TYPE=shmiggle4",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testTooManyPositional() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional1",
                "positional2",
                "positional3",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testNotEnoughPositional() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testUnexpectedPositional() {
        final String[] args = {
                "T=17",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "SHMIGGLE_TYPE=shmiggle2",
                "positional"
        };
        final OptionsWithoutPositional fo = new OptionsWithoutPositional();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test(expectedExceptions = CommandLineParserDefinitionException.class)
    public void testOptionDefinitionCaseClash() {
        final OptionsWithCaseClash options = new OptionsWithCaseClash();
        new CommandLineParser(options);
        Assert.fail("Should not be reached.");
    }

    @Test
    public void testOptionUseCaseClash() {
        final String[] args = {
                "FROBNICATION_FLAVOR=BAR",
                "FrOBNICATION_fLAVOR=BAR",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }

    @Test
    public void testOptionsFile() throws Exception {
        final File optionsFile = File.createTempFile("clp.", ".options");
        optionsFile.deleteOnExit();
        final PrintWriter writer = new PrintWriter(optionsFile);
        writer.println("T=18");
        writer.println("TRUTHINESS=True");
        writer.println("SHMIGGLE_TYPE=shmiggle0");
        writer.println("STRANGE_OPTION=shmiggle0");
        writer.close();
        final String[] args = {
                "OPTIONS_FILE=" + optionsFile.getPath(),
                // Multiple options files are allowed
                "OPTIONS_FILE=" + optionsFile.getPath(),
                "T=17",
                "FROBNICATION_FLAVOR=BAR",
                "TRUTHINESS=False",
                "SHMIGGLE_TYPE=shmiggle1",
                "positional1",
                "positional2",
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(fo.positionalArguments.size(), 2);
        final File[] expectedPositionalArguments = { new File("positional1"), new File("positional2")};
        Assert.assertEquals(fo.positionalArguments.toArray(), expectedPositionalArguments);
        Assert.assertEquals(fo.FROBNICATION_THRESHOLD.intValue(), 17);
        Assert.assertEquals(fo.FROBNICATION_FLAVOR, FrobnicationFlavor.BAR);
        Assert.assertEquals(fo.SHMIGGLE_TYPE.size(), 3);
        final String[] expectedShmiggleTypes = {"shmiggle0", "shmiggle0", "shmiggle1"};
        Assert.assertEquals(fo.SHMIGGLE_TYPE.toArray(), expectedShmiggleTypes);
        Assert.assertFalse(fo.TRUTHINESS);
    }


    /**
     * In an options file, should not be allowed to override an option set on the command line
     * @throws Exception
     */
    @Test
    public void testOptionsFileWithDisallowedOverride() throws Exception {
        final File optionsFile = File.createTempFile("clp.", ".options");
        optionsFile.deleteOnExit();
        final PrintWriter writer = new PrintWriter(optionsFile);
        writer.println("T=18");
        writer.close();
        final String[] args = {
                "T=17",
                "OPTIONS_FILE=" + optionsFile.getPath()
        };
        final FrobnicateOptions fo = new FrobnicateOptions();
        final CommandLineParser clp = new CommandLineParser(fo);
        Assert.assertFalse(clp.parseOptions(System.err, args));
    }
    
    @DataProvider(name="mutexScenarios")
    public Object[][] mutexScenarios() {
        final Object[][] scenarios = new Object[][] {
                { "pass", new String[] {"A=1", "B=2"}, true },
                { "no args", new String[0], false },
                { "1 of group required", new String[] {"A=1"}, false },
                { "mutex", new String[]  {"A=1", "Y=3"}, false },
                { "mega mutex", new String[]  {"A=1", "B=2", "Y=3", "Z=1", "M=2", "N=3"}, false }
        };
        return scenarios; 
    }
    
    @Test(dataProvider="mutexScenarios")
    public void testMutex(final String testName, final String[] args, final boolean expected) {
        final MutextOptions o = new MutextOptions();
        final CommandLineParser clp = new CommandLineParser(o);
        Assert.assertEquals(clp.parseOptions(System.err, args), expected);
    }

    class UninitializedCollectionOptions {
        @Option
        public List<String> LIST;
        @Option
        public ArrayList<String> ARRAY_LIST;
        @Option
        public HashSet<String> HASH_SET;
        @PositionalArguments
        public Collection<File> COLLECTION;

    }

    @Test
    public void testUninitializedCollections() {
        final UninitializedCollectionOptions o = new UninitializedCollectionOptions();
        final CommandLineParser clp = new CommandLineParser(o);
        final String[] args = {"LIST=L1", "LIST=L2", "ARRAY_LIST=S1", "HASH_SET=HS1", "P1", "P2"};
        Assert.assertTrue(clp.parseOptions(System.err, args));
        Assert.assertEquals(o.LIST.size(), 2);
        Assert.assertEquals(o.ARRAY_LIST.size(), 1);
        Assert.assertEquals(o.HASH_SET.size(), 1);
        Assert.assertEquals(o.COLLECTION.size(), 2);
    }

    class UninitializedCollectionThatCannotBeAutoInitializedOptions {
        @Option
        public Set<String> SET;
    }

    @Test(expectedExceptions = CommandLineParserDefinitionException.class)
    public void testCollectionThatCannotBeAutoInitialized() {
        final UninitializedCollectionThatCannotBeAutoInitializedOptions o = new UninitializedCollectionThatCannotBeAutoInitializedOptions();
        new CommandLineParser(o);
        Assert.fail("Exception should have been thrown");
    }
}
