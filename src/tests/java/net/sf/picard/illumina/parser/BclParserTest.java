package net.sf.picard.illumina.parser;

import static net.sf.picard.illumina.parser.BinTdUtil.A;
import static net.sf.picard.illumina.parser.BinTdUtil.C;
import static net.sf.picard.illumina.parser.BinTdUtil.G;
import static net.sf.picard.illumina.parser.BinTdUtil.T;
import static net.sf.picard.illumina.parser.BinTdUtil.P;

import net.sf.picard.PicardException;
import net.sf.picard.io.IoUtil;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

public class BclParserTest {
    public static final File TEST_DATA_DIR = new File("testdata/net/sf/picard/illumina/CompleteIlluminaDir/Intensities/BaseCalls/L003");
    public static final String READ_STRUCTURE        = "25T8B25T";
    public static final String READ_STRUCTURE_WSKIPS = "25S8B25S";
    public static final String READ_STRUCTURE_WSKIPS_PARTIAL = "10T5S10T8B5T20S";
    public static final String READ_STRUCTURE_WSKIPS_BAD     = "10T5S10T2B4S2B5T20S";
    public static final int [] READ_LENGTHS = new int[]{25,8,25};
    public static final int LANE = 3;
    public static final IlluminaDataType DATA_TYPES [] = {IlluminaDataType.BaseCalls, IlluminaDataType.QualityScores};
    public static final int TILE_SIZES = 20;


    public static CycleIlluminaFileMap makeCycleIlluminaFileMap(final File tdDir, final int [] tiles, final int [] outputCycles) {
        final CycleIlluminaFileMap fileMap = new CycleIlluminaFileMap();

        for(final Integer tile : tiles) {
            fileMap.put(tile, new CycleFilesIterator(tdDir, LANE, tile, outputCycles, ".bcl"));
        }

        return fileMap;
    }

    public static Integer [] boxArr(final int [] ints) {
        final Integer [] boxArr = new Integer[ints.length];
        for(int i = 0; i < boxArr.length; i++) {
            boxArr[i] = ints[i];
        }

        return boxArr;
    }

    @DataProvider(name="tileMaps")
    public Object [][] getTileMaps() {
        return new Object[][] {
            //TILES, NUM_CLUSTER TO BE READ, SEEK AT THIS READ, INDEX TO TILETOSEEK, ORDERED TILE INDEX (FOR OUT OF ORDER TILES)
            {new int[]{1101, 1201, 2101}, 60, -1, -1, -1},
            {new int[]{1101, 2101, 1201}, 60, -1, -1, -1},
            {new int[]{2101, 1201},       40, -1, -1, -1},
            {new int[]{1101, 2101},       40, -1, -1, -1},
            {new int[]{1101},             20, -1, -1, -1},

            //Cases with seeking
            {new int[]{1101, 1201, 2101}, 86, 25, 0, 0},
            {new int[]{1101, 2101, 1201}, 86, 25, 0, 0},
            {new int[]{2101, 1201},       40, 19, 0, 1},
            {new int[]{1101, 2101},       76, 35, 0, 0},
            {new int[]{1101},             26,  5, 0, 0}
        };
    }

    public void compareClusterToBclData(final ClusterData cluster, final BclData bclData, final int clusterNum, final int countNum) {
        final byte [][] bases = bclData.getBases();
        final byte [][] qualities = bclData.getQualities();

        Assert.assertEquals(bases.length,     cluster.getNumReads(),  "At cluster num " + clusterNum);
        Assert.assertEquals(qualities.length, cluster.getNumReads(),  "At cluster num " + clusterNum);

        for(int i = 0; i < cluster.getNumReads(); i++) {
            Assert.assertEquals(bases[i],     cluster.getRead(i).getBases(),     " Bases differ for read "     + i + " at cluster num " + clusterNum + " at cluster count " + countNum);
            Assert.assertEquals(qualities[i], cluster.getRead(i).getQualities(), " Qualities differ for read " + i + " at cluster num " + clusterNum + " at cluster count " + countNum);
        }
    }

    public void fullBclParserTestImpl(final File dir, final String readStructure, final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        final ReadStructure rs = new ReadStructure(readStructure);
        final OutputMapping outputMapping = new OutputMapping(rs);

        final BclParser bclParser = new BclParser(dir, 3, makeCycleIlluminaFileMap(dir, tiles, outputMapping.getOutputCycles()), outputMapping);
        final Map<Integer, ClusterData> testData = BinTdUtil.clusterData(LANE, Arrays.asList(boxArr(tiles)), readStructure, DATA_TYPES);

        int count = 0;
        int readNum = 0;
        while (bclParser.hasNext()) {
            final BclData bclData = bclParser.next();
            if (testData.containsKey(readNum)) {
                compareClusterToBclData(testData.get(readNum), bclData, readNum, count);
            }

            if (count == seekAfter) {
                bclParser.seekToTile(tiles[newTileIndex]);
                readNum = (orderedTileIndex * TILE_SIZES);
            } else {
                readNum++;
            }
            count++;
        }
        Assert.assertEquals(count, size);
    }

    public static void deleteBclFiles(final File laneDirectory, final String readStructure) {
        final ReadStructure rs = new ReadStructure(readStructure);
        int index = 1;
        for(final ReadDescriptor rd : rs.descriptors) {
            if(rd.type == ReadType.S) {
                for(int i = index; i < index + rd.length; i++) {
                    final File cycleDir = new File(laneDirectory, "C" + i + ".1");
                    final File [] cycleFiles = cycleDir.listFiles();
                    for(final File toDelete : cycleFiles) {
                        if(!toDelete.delete()) {
                            throw new RuntimeException("Couldn't delete file " + toDelete.getAbsolutePath());
                        }
                    }
                }
            }

            index += rd.length;
        }
    }

    @Test(dataProvider = "tileMaps")
    public void fullBclParserTest(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        fullBclParserTestImpl(TEST_DATA_DIR, READ_STRUCTURE, tiles, size, seekAfter, newTileIndex, orderedTileIndex);
    }


    @Test(dataProvider = "tileMaps")
    public void fullBclParserTestWSkips(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        fullBclParserTestImpl(TEST_DATA_DIR, READ_STRUCTURE_WSKIPS, tiles, size, seekAfter, newTileIndex, orderedTileIndex);
    }

    @Test(dataProvider = "tileMaps")
    public void fullBclParserTestWDeletedSkips(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        fullBclParserTestWDeletedSkipsImpl(tiles, size, seekAfter, newTileIndex, orderedTileIndex, READ_STRUCTURE_WSKIPS);
    }

    @Test(dataProvider = "tileMaps")
    public void fullBclParserTestWPartiallyDeletedSkips(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        fullBclParserTestWDeletedSkipsImpl(tiles, size, seekAfter, newTileIndex, orderedTileIndex, READ_STRUCTURE_WSKIPS_PARTIAL);
    }

    @Test(dataProvider = "tileMaps", expectedExceptions = RuntimeException.class)
    public void fullBclParserTestWBadDeletedSkips(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex) {
        fullBclParserTestWDeletedSkipsImpl(tiles, size, seekAfter, newTileIndex, orderedTileIndex, READ_STRUCTURE_WSKIPS_BAD);
    }

    public void fullBclParserTestWDeletedSkipsImpl(final int[] tiles, final int size, final int seekAfter, final int newTileIndex, final int orderedTileIndex, final String readStructure) {
        final File basecallDir = IoUtil.createTempDir("bclParserTest", "BaseCalls");

        Exception exc = null;
        try {
            final File l003 = new File(basecallDir, "L003");
            if(!l003.mkdir()) {
                throw new RuntimeException("Couldn't make lane dir " + l003.getAbsolutePath());
            }

            copyBcls(TEST_DATA_DIR, l003);
            deleteBclFiles(l003, readStructure);
            fullBclParserTestImpl(l003, READ_STRUCTURE_WSKIPS, tiles, size, seekAfter, newTileIndex, orderedTileIndex);
        } catch(Exception thrExc) {
            exc = thrExc;
        } finally {
            IoUtil.deleteDirectoryTree(basecallDir);
        }
        if(exc != null) {
            if(exc.getClass() == PicardException.class) {
                throw new PicardException(exc.getMessage());
            }
            throw new RuntimeException(exc);
        }
    }

    //Custom copy function to avoid copying .svn files etc...
    public static void copyBcls(final File srcLaneDir, final File dstDir) {
        final File [] listFiles = srcLaneDir.listFiles();

        for(final File dir : listFiles) {
            if(dir.isDirectory()) {
                File cycleDir = null;

                for(final File file : dir.listFiles()) {
                    if(file.getName().endsWith(".bcl")) {
                        if(cycleDir == null) {
                            cycleDir = new File(dstDir, dir.getName());
                            if(!cycleDir.mkdir()) {
                                throw new RuntimeException("Couldn't make directory (" + cycleDir.getAbsolutePath() + ")");
                            }
                        }

                        IoUtil.copyFile(file, new File(cycleDir, file.getName()));
                    }
                }
            }
        }
    }



    //Helper byte [] tuple for EAMSS testing
    class BasesAndQuals {
        public final byte [] bases;
        public final byte [] quals;
        public final byte [] maskedQuals;
        public BasesAndQuals(final byte[] bases, final byte[] quals, final Integer maskStart) {
            this.bases = bases;
            this.quals = quals;
            this.maskedQuals = qualsMaskedFrom(maskStart);
        }

        private byte[] qualsMaskedFrom(final Integer maskStart) {
            final byte [] maskedQuals = Arrays.copyOf(quals, quals.length);
            if(maskStart != null) {
                for(int i = maskStart; i < maskedQuals.length; i++) {
                    maskedQuals[i] = BclParser.MASKING_QUALITY;
                }
            }
            return maskedQuals;
        }

        public String toString() {
            return "BasesAndQuals( " + basesToString() + ", " + qualsToString(quals) + ", " + qualsToString(maskedQuals) + ")";
        }

        public String basesToString() {
            final StringBuilder sb = new StringBuilder(bases.length);
            for(final byte base : bases ) {
                switch(base) {
                    case A:
                        sb.append("A ");
                        break;
                    case C:
                        sb.append("C ");
                        break;
                    case G:
                        sb.append("G ");
                        break;
                    case T:
                        sb.append("T ");
                        break;
                    case P:
                        sb.append(". ");
                        break;

                    default:
                        throw new RuntimeException("THIS SHOULD NOT HAPPEN!  Bad byte in bases!");
                }
            }

            return sb.toString();
        };

        public String qualsToString(final byte [] qualsToConvert) {

            final StringBuilder sb = new StringBuilder(bases.length);
            for(final byte qual : qualsToConvert ) {
                sb.append(String.valueOf((int) qual));
                sb.append(",");
            }

            return sb.toString();
        }
    }

    @DataProvider(name = "eamssDataNo10GSeries")
    public Object[][] eamssDataNo10GSeries() {

       return new Object[][]{
           //Non-masking cases

           //tally very negative, 9G's
           {new BasesAndQuals(new byte []{ G, G,  G,  G,  G,  G,  G,  G,  G},
                              new byte []{13, 7, 35, 32, 31, 33, 31, 26, 29},
                              null)},

           //tally barely negative
           {new BasesAndQuals(new byte []{ G, G,  G,  G,  G,  G,  G,  G,  G},
                              new byte []{13, 7, 35, 26, 18, 19, 35,  8, 33},
                              null)},

           //Reaches 0, A stretch of more than 10 other types of bases
           {new BasesAndQuals(new byte []{ A, C,  C,  C,  C,  C,  C,  C,  C,  C,  C,  C,  T,  G,  G,  C,  T,  A,  A},
                              new byte []{ 7, 8, 33,  7,  2, 33, 16, 17, 19,  7,  6,  5, 35,  2, 33, 22, 18, 16, 25},
                              null)},

           //Stays at 0, Stretches of G's Separated
           {new BasesAndQuals(new byte []{ T, G,  G,  G,  G,  G,  G,  G,  P,  P,  G,  G,  G,  G,  G,  G,  G,  T,  A,  A,  G,  G,  G},
                              new byte []{ 7, 8, 33,  7,  2, 33, 16, 17,  2,  2,  6,  5, 35,  2, 33, 22, 18, 16, 25, 33, 32, 16, 18},
                              null)},

           //shorter
           {new BasesAndQuals(new byte []{ T, A,  C},
                              new byte []{ 25, 16, 16},
                              null)},

           //Longer
           {new BasesAndQuals(new byte []{ T,   A,  C,  G,  G, P, P,  T,  C, C,  C,  C,  T,  T,  T,  G,  G,  G, A,  T, G,  C,  A, T,  A,  C,  G,  G, P, P,  T,  C,  C,  C, C, T,  T,  T,  G,  G,  G,  A, T,   G,  C,  A},
                              new byte []{ 25, 16, 16, 33, 22, 2, 2, 33, 35, 3, 31, 38, 22, 19, 25, 16, 16, 31, 30, 2, 2, 33, 26, 3, 31, 38, 22, 19, 2, 2, 30, 27, 28, 16, 2, 2, 30, 16, 19, 21, 22, 17, 19, 16, 16, 16},
                              null)},


            //Masking-Cases

           //tally very positive, 9Gs                                  X - Mask From here
           {new BasesAndQuals(new byte []{ G, G,  G,  G,  G,  G,  G,   G,   G},
                              new byte []{13, 7, 35, 32,  2, 16, 33,  14,  19},
                              7)},

           //tally barely negative                        X - Mask from here
           {new BasesAndQuals(new byte []{ G, G,  G,  G,  G,  G,  G,  G,  G},
                              new byte []{13, 7, 35, 33, 18,  2,  6,  8, 33},
                              4)},
           //Reaches 0, A stretch of more than 10 other types of bases                X - Mask From here
           {new BasesAndQuals(new byte []{ A, C,  C,  C,  C,  C,  C,  C,  C,  C,  C,  C,  T,  G,  G,  C,  T,  A,  A},
                              new byte []{ 7, 8, 33,  7,  2, 33, 16, 17, 19, 32, 33,  5,  8,  2,  2,  2, 33, 16, 25},
                              11)},
           //Stays at 0, Stretches of G's Separated                                                       X- Mask from here
           {new BasesAndQuals(new byte []{ T, G,  G,  G,  G,  G,  G,  G,  P,  P,  G,  G,  G,  G,  G,  G,  G,  T,  A,  A,  G,  G,  G},
                              new byte []{ 7, 8, 33,  7,  2, 33, 16, 17,  2,  2,  6,  5, 35,  2, 33, 30, 13, 16,  7,  2,  2, 16, 18},
                              16)},
           //shorter                       X - Mask from here
           {new BasesAndQuals(new byte []{ T,   A,   C},
                              new byte []{ 2,  11,  13},
                              0)},
           //Longer                                                                                                                           X- Mask from here
           {new BasesAndQuals(new byte []{ T,   A,  C,  G,  G, P, P,  T,  C, C,  C,  C,  T,  T,  T,  G,  G,  G, A,  T, G,  C,  A, T,  A,  C,  G,  G, P, P,  T,  C,  C,  C, C, T,  T,  T,  G,  G,  G,  A,  T,   G,  C,  A},
                              new byte []{ 25, 16, 16, 33, 22, 2, 2, 33, 35, 3, 31, 38, 22, 19, 25, 16, 16, 31, 30, 2, 2, 33, 26, 3, 31, 38, 22, 19, 2, 2, 30, 27, 28, 16, 2, 2, 30, 16, 19, 21, 22,  2, 19,  16,  2,  2},
                              26)}
        };
    }

    /** For more information on EAMSS check BclParser and the large comment above runEamssForReadInPlace **/
    @DataProvider(name = "eamssDataWithGSeries")
    public Object [][] eamssTestDat() {
        return new Object[][]{

        //9 G's followed by tally max                     X - Mask from here
        {new BasesAndQuals(new byte []{ A, C,  G, G, T,   G,  G,  G,  G,  G,  G,   G,  G,   G, A,  C,  T},
                           new byte []{ 7, 8, 33, 7, 12, 33, 16, 17,  2,  2, 32,  35, 35,  35, 2, 15, 9},
                           5)},
        //9 G's surpassed by tally max  X - Mask from here
        {new BasesAndQuals(new byte []{ A, C, G, G, T, G,  G,  G,  G,  G,  G,   G,  G,   G, A,  C,  T},
                           new byte []{ 7, 8, 2, 7, 2, 2, 16, 17,  2,  2, 32,  35, 35,  35, 2, 15, 9},
                           0)},
        //10 G's ending before tally max                                                        X - Mask from here
        {new BasesAndQuals(new byte []{ A, C, G, G, T, G,  G,  G,  G,  G,  G,   G,  G,   G, A,  C,  T},
                           new byte []{ 7, 8, 2, 7, 2, 2, 16, 17,  2,  2, 32,  35, 35,  35, 33, 15, 9},
                           15)},
        //10 G's ending on tally max                         X - Mask from here Is this wrong?
        {new BasesAndQuals(new byte []{ A,   C,  C,  G,  C,  C, G,  G,   G,  G,  G,  G,  G,  G,  G,  G, T, T,  A},
                           new byte []{ 33, 31, 29, 32, 28, 27, 30, 18, 18, 18, 18, 19, 19, 19, 33, 18, 9, 9, 19},
                           6)},
        //10 G's no masking
        {new BasesAndQuals(new byte []{ A,   C,  C,  G,  C,  C, G,  G,   G,  G,  G,  G,  G,  G,  G,  G,  T,  T,  A},
                           new byte []{ 33, 31, 29, 32, 28, 27, 30, 18, 18, 18, 18, 19, 19, 19, 33, 18, 33, 32, 34},
                           null)},
        //10 G' with an exception                               X - Mask from here
        {new BasesAndQuals(new byte []{ A,   C,  C,  G,  C,  C, G,  G,   G,  G,  A,  G,  G,  G,  G,  G, T, T,  A},
                           new byte []{ 33, 31, 29, 32, 28, 27, 30, 18, 18, 18, 18, 19, 19, 19, 33, 18, 9, 9, 19},
                           6)},
        //longer than 10 G's                      X - Mask from here
        {new BasesAndQuals(new byte []{ A,   C,   G,  G,  G,  C,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G, T, T,  A},
                           new byte []{ 33, 31,  29, 32, 28, 16, 33, 18, 18, 18, 18, 19, 19, 19, 33, 18, 3, 9, 19},
                           2)},
        //longer than 10 G's                                      X - Mask from here
        {new BasesAndQuals(new byte []{ A,   C,   G,  G,  C,  C,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G, G},
                           new byte []{ 33, 31,  29, 32, 28, 16, 33, 18, 18, 18, 18, 19, 19, 19, 33, 34, 33, 33, 3},
                           6)},
        //longer than 10 G's
        {new BasesAndQuals(new byte []{ A,   C,   G,  G,  C,  C,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G,  G, G, T,  A,  C, T, T,  G,  G,  G,  G,  G,  G,  G,  G,   G,  G,  G,  G,  G},
                           new byte []{ 33, 31,  29, 32, 28, 16, 33, 18, 18, 18, 18, 19, 19, 19, 33, 34, 33, 33, 3, 33, 34, 2, 4, 8, 33,  7, 35, 15, 16, 31, 30, 38,  16, 15, 22, 29, 25},
                           null)}
        };
    }

    public void testEamss(final BasesAndQuals bq) {
        final byte[] bases = Arrays.copyOf(bq.bases, bq.bases.length);
        final byte[] quals = Arrays.copyOf(bq.quals, bq.quals.length);

        TestBclParser.runEamssForReadInPlace(bases, quals);
        Assert.assertEquals(bases, bq.bases);
        Assert.assertEquals(quals, bq.maskedQuals);
    }

    @Test(dataProvider = "eamssDataNo10GSeries")
    public void eamssParsingTestNoGSeries(final BasesAndQuals bq) {
        testEamss(bq);
    }

    @Test(dataProvider = "eamssDataWithGSeries")
    public void eamssParsingTestWithGSeries(final BasesAndQuals bq) {
        testEamss(bq);
    }
}

class TestBclParser extends BclParser{
    public TestBclParser() {
        super(null, 1, null, new OutputMapping(new ReadStructure("1T")));
    }
}
