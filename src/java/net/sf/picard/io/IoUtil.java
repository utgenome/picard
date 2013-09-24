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
package net.sf.picard.io;

import net.sf.picard.PicardException;
import net.sf.picard.util.IterableOnceIterator;
import net.sf.samtools.Defaults;
import net.sf.samtools.util.CloserUtil;
import net.sf.samtools.util.CollectionUtil;
import net.sf.samtools.util.RuntimeIOException;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.apache.tools.bzip2.CBZip2OutputStream;

/**
 * A class for utility methods that wrap or aggregate functionality in Java IO.
 *
 * @author Tim Fennell
 */
public class IoUtil extends net.sf.samtools.util.IOUtil {
    /**
     * Checks that a file is non-null, exists, is not a directory and is readable.  If any
     * condition is false then a runtime exception is thrown.
     *
     * @param file the file to check for readability
     */
    public static void assertFileIsReadable(final File file) {
        if (file == null) {
			throw new IllegalArgumentException("Cannot check readability of null file.");
		} else if (!file.exists()) {
            throw new PicardException("Cannot read non-existent file: " + file.getAbsolutePath());
        }
        else if (file.isDirectory()) {
            throw new PicardException("Cannot read file because it is a directory: " + file.getAbsolutePath());
        }
        else if (!file.canRead()) {
            throw new PicardException("File exists but is not readable: " + file.getAbsolutePath());
        }
    }

    /**
     * Checks that a file is non-null, and is either extent and writable, or non-existent but
     * that the parent directory exists and is writable. If any
     * condition is false then a runtime exception is thrown.
     *
     * @param file the file to check for writability
     */
    public static void assertFileIsWritable(final File file) {
        if (file == null) {
			throw new IllegalArgumentException("Cannot check readability of null file.");
		} else if (!file.exists()) {
            // If the file doesn't exist, check that it's parent directory does and is writable
            final File parent = file.getAbsoluteFile().getParentFile();
            if (!parent.exists()) {
                throw new PicardException("Cannot write file: " + file.getAbsolutePath() + ". " +
                        "Neither file nor parent directory exist.");
            }
            else if (!parent.isDirectory()) {
                throw new PicardException("Cannot write file: " + file.getAbsolutePath() + ". " +
                        "File does not exist and parent is not a directory.");
            }
            else if (!parent.canWrite()) {
                throw new PicardException("Cannot write file: " + file.getAbsolutePath() + ". " +
                        "File does not exist and parent directory is not writable..");
            }
        }
        else if (file.isDirectory()) {
            throw new PicardException("Cannot write file because it is a directory: " + file.getAbsolutePath());
        }
        else if (!file.canWrite()) {
            throw new PicardException("File exists but is not writable: " + file.getAbsolutePath());
        }
    }

    /**
     * Checks that a directory is non-null, extent, writable and a directory 
     * otherwise a runtime exception is thrown.
     *
     * @param dir the dir to check for writability
     */
    public static void assertDirectoryIsWritable(final File dir) {
        if (dir == null) {
            throw new IllegalArgumentException("Cannot check readability of null file.");
        } 
        else if (!dir.exists()) {
            throw new PicardException("Directory does not exist: " + dir.getAbsolutePath());
        }
        else if (!dir.isDirectory()) {
            throw new PicardException("Cannot write to directory because it is not a directory: " + dir.getAbsolutePath());
        }
        else if (!dir.canWrite()) {
            throw new PicardException("Directory exists but is not writable: " + dir.getAbsolutePath());
        }
    }

    /**
     * Checks that a directory is non-null, extent, readable and a directory
     * otherwise a runtime exception is thrown.
     *
     * @param dir the dir to check for writability
     */
    public static void assertDirectoryIsReadable(final File dir) {
        if (dir == null) {
            throw new IllegalArgumentException("Cannot check readability of null file.");
        }
        else if (!dir.exists()) {
            throw new PicardException("Directory does not exist: " + dir.getAbsolutePath());
        }
        else if (!dir.isDirectory()) {
            throw new PicardException("Cannot read from directory because it is not a directory: " + dir.getAbsolutePath());
        }
        else if (!dir.canRead()) {
            throw new PicardException("Directory exists but is not readable: " + dir.getAbsolutePath());
        }
    }

    /**
     * Checks that the two files are the same length, and have the same content, otherwise throws a runtime exception.
     */
    public static void assertFilesEqual(final File f1, final File f2) {
        try {
            if (f1.length() != f2.length()) {
                throw new PicardException("Files " + f1 + " and " + f2 + " are different lengths.");
            }
            final FileInputStream s1 = new FileInputStream(f1);
            final FileInputStream s2 = new FileInputStream(f2);
            final byte[] buf1 = new byte[1024 * 1024];
            final byte[] buf2 = new byte[1024 * 1024];
            int len1;
            while ((len1 = s1.read(buf1)) != -1) {
                final int len2 = s2.read(buf2);
                if (len1 != len2) {
                    throw new PicardException("Unexpected EOF comparing files that are supposed to be the same length.");
                }
                if (!Arrays.equals(buf1, buf2)) {
                    throw new PicardException("Files " + f1 + " and " + f2 + " differ.");
                }
            }
            s1.close();
            s2.close();
        } catch (IOException e) {
            throw new PicardException("Exception comparing files " + f1 + " and " + f2, e);
        }

    }

    /**
     * Checks that a file is of non-zero length
     */
    public static void assertFileSizeNonZero(final File file) {
        if (file.length() == 0) {
            throw new PicardException(file.getAbsolutePath() + " has length 0");
        }
    }

    /**
     * Opens a file for reading, decompressing it if necessary
     *
     * @param file  The file to open
     * @return the input stream to read from
     */
    public static InputStream openFileForReading(final File file) {

        try {
            if(file.getName().endsWith(".bz2")) {
                return openBzipFileForReading(file);
            }
            if (file.getName().endsWith(".gz") ||
                file.getName().endsWith(".bfq"))  {
                return openGzipFileForReading(file);
            }
            //TODO: Other compression formats
            else {
                return new FileInputStream(file);
            }
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file: " + file.getName(), ioe);
        }

    }

    /**
     * Opens a GZIP-encoded file for reading, decompressing it if necessary
     *
     * @param file  The file to open
     * @return the input stream to read from
     */
    public static InputStream openGzipFileForReading(final File file) {
        
        try {
            return new GZIPInputStream(new FileInputStream(file));
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file: " + file.getName(), ioe);
        }
    }

    /**
     * Opens a GZIP-encoded file for reading, decompressing it if necessary
     *
     * @param file  The file to open
     * @return the input stream to read from
     */
    public static InputStream openBzipFileForReading(final File file) {

        try {
            final FileInputStream fis = new FileInputStream(file);
            if(fis.read() != 66 || fis.read() != 90) { //Read magic number 'BZ' or else CBZip2InputStream will complain about it
                fis.close();
                throw new PicardException(file.getAbsolutePath() + " is not a BZIP file.");
            }

            return new CBZip2InputStream(fis);
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file: " + file.getName(), ioe);
        }
    }

    /**
     * Opens a file for writing, overwriting the file if it already exists
     *
     * @param file  the file to write to
     * @return the output stream to write to
     */
    public static OutputStream openFileForWriting(final File file) {
        return openFileForWriting(file, false);
    }

    /**
     * Opens a file for writing
     *
     * @param file  the file to write to
     * @param append    whether to append to the file if it already exists (we overwrite it if false)
     * @return the output stream to write to
     */
    public static OutputStream openFileForWriting(final File file, final boolean append) {

        try {
            if (file.getName().endsWith(".bz2")) {
                return openBzipFileForWriting(file, append);
            }
            if (file.getName().endsWith(".gz") ||
                file.getName().endsWith(".bfq")) {
                return openGzipFileForWriting(file, append);
            }
            //TODO: Other compression formats
            else {
                return new FileOutputStream(file, append);
            }
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file for writing: " + file.getName(), ioe);
        }
    }

    /**
     * Preferred over PrintStream and PrintWriter because an exception is thrown on I/O error
     */
    public static BufferedWriter openFileForBufferedWriting(final File file, final boolean append) {
        return new BufferedWriter(new OutputStreamWriter(openFileForWriting(file, append)), Defaults.BUFFER_SIZE);
    }

    /**
     * Preferred over PrintStream and PrintWriter because an exception is thrown on I/O error
     */
    public static BufferedWriter openFileForBufferedWriting(final File file) {
        return openFileForBufferedWriting(file, false);
    }

    /**
     * Preferred over PrintStream and PrintWriter because an exception is thrown on I/O error
     */
    public static BufferedWriter openFileForBufferedUtf8Writing(final File file) {
        return new BufferedWriter(new OutputStreamWriter(
            openFileForWriting(file), Charset.forName("UTF-8")), Defaults.BUFFER_SIZE);
    }

    /**
     * Opens a file for reading, decompressing it if necessary
     *
     * @param file  The file to open
     * @return the input stream to read from
     */
    public static BufferedReader openFileForBufferedUtf8Reading(final File file) {
        return new BufferedReader(new InputStreamReader(openFileForReading(file), Charset.forName("UTF-8")));
    }

    /**
     * Opens a GZIP encoded file for writing
     *
     * @param file  the file to write to
     * @param append    whether to append to the file if it already exists (we overwrite it if false)
     * @return the output stream to write to
     */
    public static OutputStream openGzipFileForWriting(final File file, final boolean append) {

        try {
            return new CustomGzipOutputStream(new FileOutputStream(file, append),
                                              Defaults.BUFFER_SIZE,
                                              Defaults.COMPRESSION_LEVEL);
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file for writing: " + file.getName(), ioe);
        }
    }

    /**
     * Opens a BZIP encoded file for writing
     *
     * @param file  the file to write to
     * @param append    whether to append to the file if it already exists (we overwrite it if false)
     * @return the output stream to write to
     */
    public static OutputStream openBzipFileForWriting(final File file, final boolean append) {

        try {

            final FileOutputStream fos = new FileOutputStream(file, append);
            fos.write(66); //write magic number 'BZ' because CBZip2OutputStream does not do it for you
            fos.write(90);
            return new BufferedOutputStream(new CBZip2OutputStream(fos), Defaults.BUFFER_SIZE);
        }
        catch (IOException ioe) {
            throw new PicardException("Error opening file for writing: " + file.getName(), ioe);
        }
    }

    /**
     * Utility method to copy the contents of input to output. The caller is responsible for
     * opening and closing both streams.
     * 
     * @param input contents to be copied
     * @param output destination
     */
    public static void copyStream(final InputStream input, final OutputStream output) {
        try {
            final byte[] buffer = new byte[Defaults.BUFFER_SIZE];
            int bytesRead = 0;
            while((bytesRead = input.read(buffer)) > 0) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new PicardException("Exception copying stream", e);
        }
    }

    /**
     * Copy input to output, overwriting output if it already exists.
     */
    public static void copyFile(final File input, final File output) {
        try {
            final InputStream is = new FileInputStream(input);
            final OutputStream os = new FileOutputStream(output);
            copyStream(is, os);
            os.close();
            is.close();
        } catch (IOException e) {
            throw new PicardException("Error copying " + input + " to " + output, e);
        }
    }

    /**
     * 
     * @param directory
     * @param regexp
     * @return list of files matching regexp.
     */
    public static File[] getFilesMatchingRegexp(final File directory, final String regexp) {
        final Pattern pattern = Pattern.compile(regexp);
        return getFilesMatchingRegexp(directory, pattern);
    }

    public static File[] getFilesMatchingRegexp(final File directory, final Pattern regexp) {
        return directory.listFiles( new FilenameFilter() {
            public boolean accept(final File dir, final String name) {
                return regexp.matcher(name).matches();
            }
        });
    }

    /**
     * Delete the given file or directory.  If a directory, all enclosing files and subdirs are also deleted.
     */
    public static boolean deleteDirectoryTree(final File fileOrDirectory) {
        boolean success = true;

        if (fileOrDirectory.isDirectory()) {
            for (final File child : fileOrDirectory.listFiles()) {
                success = success && deleteDirectoryTree(child);
            }
        }

        success = success && fileOrDirectory.delete();
        return success;
    }

    /**
     * Returns the size (in bytes) of the file or directory and all it's children.
     */
    public static long sizeOfTree(final File fileOrDirectory) {
        long total = fileOrDirectory.length();
        if (fileOrDirectory.isDirectory()) {
            for (final File f : fileOrDirectory.listFiles()) {
                total += sizeOfTree(f);
            }
        }

        return total;
    }

    /**
     *
     * Copies a directory tree (all subdirectories and files) recursively to a destination
     */
    public static void copyDirectoryTree(final File fileOrDirectory, final File destination) {
        if (fileOrDirectory.isDirectory()) {
            destination.mkdir();
            for(final File f : fileOrDirectory.listFiles()) {
                final File destinationFileOrDirectory =  new File(destination.getPath(),f.getName());
                if (f.isDirectory()){
                    copyDirectoryTree(f,destinationFileOrDirectory);
                }
                else {
                    copyFile(f,destinationFileOrDirectory);
                }
            }
        }
    }

    /**
     * Create a temporary subdirectory in the default temporary-file directory, using the given prefix and suffix to generate the name.
     * Note that this method is not completely safe, because it create a temporary file, deletes it, and then creates
     * a directory with the same name as the file.  Should be good enough.
     *
     * @param prefix The prefix string to be used in generating the file's name; must be at least three characters long
     * @param suffix The suffix string to be used in generating the file's name; may be null, in which case the suffix ".tmp" will be used
     * @return File object for new directory
     */
    public static File createTempDir(final String prefix, final String suffix) {
        try {
            final File tmp = File.createTempFile(prefix, suffix);
            if (!tmp.delete()) {
                throw new PicardException("Could not delete temporary file " + tmp);
            }
            if (!tmp.mkdir()) {
                throw new PicardException("Could not create temporary directory " + tmp);
            }
            return tmp;
        } catch (IOException e) {
            throw new PicardException("Exception creating temporary directory.", e);
        }
    }

    /** Checks that a file exists and is readable, and then returns a buffered reader for it. */
    public static BufferedReader openFileForBufferedReading(final File file) throws IOException {
        return new BufferedReader(new InputStreamReader(openFileForReading(file)), Defaults.BUFFER_SIZE);
	}

    /** Takes a string and replaces any characters that are not safe for filenames with an underscore */
    public static String makeFileNameSafe(final String str) {
        return str.trim().replaceAll("[\\s!\"#$%&'()*/:;<=>?@\\[\\]\\\\^`{|}~]", "_");
    }

    /** Returns the name of the file minus the extension (i.e. text after the last "." in the filename). */
    public static String basename(final File f) {
        final String full = f.getName();
        final int index = full.lastIndexOf(".");
        if (index > 0  && index > full.lastIndexOf(File.separator)) {
            return full.substring(0, index);
        }
        else {
            return full;
        }
    }

    /** Returns the name of the file extension (i.e. text after the last "." in the filename) including the . */
    public static String fileSuffix(final File f) {
        final String full = f.getName();
        final int index = full.lastIndexOf(".");
        if (index > 0 && index > full.lastIndexOf(File.separator)) {
            return full.substring(index);
        } else {
            return null;
        }
    }

    /** Returns the full path to the file with all symbolic links resolved **/
    public static String getFullCanonicalPath(final File file) {
        try {
            File f = file.getCanonicalFile();
            String canonicalPath = "";
            while (f != null  && !f.getName().equals("")) {
                canonicalPath = "/" + f.getName() + canonicalPath;
                f = f.getParentFile();
                if (f != null) f = f.getCanonicalFile();
            }
            return canonicalPath;
        } catch (IOException ioe) {
            throw new RuntimeException("Error getting full canonical path for " +
                    file + ": " + ioe.getMessage(), ioe);
        }
   }

    /**
     * Reads everything from an input stream as characters and returns a single String.
     */
    public static String readFully(final InputStream in) {
        try {
            final BufferedReader r = new BufferedReader(new InputStreamReader(in), Defaults.BUFFER_SIZE);
            final StringBuilder builder = new StringBuilder(512);
            String line = null;

            while ((line = r.readLine()) != null) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }

            return builder.toString();
        }
        catch (IOException ioe) {
            throw new RuntimeIOException("Error reading stream", ioe);
        }
    }

    /**
     * Returns an iterator over the lines in a text file. The underlying resources are automatically
     * closed when the iterator hits the end of the input, or manually by calling close().
     *
     * @param f a file that is to be read in as text
     * @return an iterator over the lines in the text file
     */
    public static IterableOnceIterator<String> readLines(final File f) {
        try {
            final BufferedReader in = IoUtil.openFileForBufferedReading(f);

            return new IterableOnceIterator<String>() {
                private String next = in.readLine();

                /** Returns true if there is another line to read or false otherwise. */
                @Override public boolean hasNext() { return next != null; }

                /** Returns the next line in the file or null if there are no more lines. */
                @Override public String next() {
                    try {
                        final String tmp = next;
                        next = in.readLine();
                        if (next == null) in.close();
                        return tmp;
                    }
                    catch (IOException ioe) { throw new RuntimeIOException(ioe); }
                }

                /** Closes the underlying input stream. Not required if end of stream has already been hit. */
                @Override public void close() throws IOException { CloserUtil.close(in); }
            };
        }
        catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    /** Returns all of the untrimmed lines in the provided file. */
    public static List<String> slurpLines(final File file) throws FileNotFoundException {
        return slurpLines(new FileInputStream(file));
    }

    public static List<String> slurpLines(final InputStream is) throws FileNotFoundException {
        /** See {@link Scanner} source for origin of delimiter used here.  */
        return tokenSlurp(is, Charset.defaultCharset(), "\r\n|[\n\r\u2028\u2029\u0085]");
    }
    
    /** Convenience overload for {@link #slurp(java.io.InputStream, java.nio.charset.Charset)} using the default charset {@link Charset#defaultCharset()}. */
    public static String slurp(final File file) throws FileNotFoundException {
        return slurp(new FileInputStream(file));
    }
    
    /** Convenience overload for {@link #slurp(java.io.InputStream, java.nio.charset.Charset)} using the default charset {@link Charset#defaultCharset()}. */
    public static String slurp(final InputStream is) {
        return slurp(is, Charset.defaultCharset());
    }

    /** Reads all of the stream into a String, decoding with the provided {@link Charset} then closes the stream quietly. */
    public static String slurp(final InputStream is, final Charset charSet) {
        return CollectionUtil.getSoleElement(tokenSlurp(is, charSet, "\\A"));
    }

    /** Tokenizes the provided input stream into memory using the given delimiter. */
    private static List<String> tokenSlurp(final InputStream is, final Charset charSet, final String delimiterPattern) {
        try {
            final Scanner s = new Scanner(is, charSet.toString()).useDelimiter(delimiterPattern);
            final LinkedList<String> tokens = new LinkedList<String>();
            while (s.hasNext()) {
                tokens.add(s.next());
            }
            return tokens;
        } finally {
            CloserUtil.close(is);
        }
    }

    /**
     * Go through the files provided and if they have one of the provided file extensions pass the file into the output
     * otherwise assume that file is a list of filenames and unfold it into the output.
     */
    public static List<File> unrollFiles(final Collection<File> inputs, final String... extensions) {
        if (extensions.length < 1) throw new IllegalArgumentException("Must provide at least one extension.");

        final Stack<File> stack = new Stack<File>();
        final List<File> output = new ArrayList<File>();
        stack.addAll(inputs);

        final Set<String> exts = new HashSet<String>();
        Collections.addAll(exts, extensions);

        while (!stack.empty()) {
            final File f = stack.pop();
            final String ext = IoUtil.fileSuffix(f);

            if (exts.contains(ext)) {
                output.add(f);
            }
            else {
                IoUtil.assertFileIsReadable(f);

                for (final String s : IoUtil.readLines(f)) {
                    if (!s.trim().isEmpty()) stack.push(new File(s.trim()));
                }
            }
        }

        return output;
    }
}

/**
 * Hacky little class used to allow us to set the compression level on a GZIP output stream which, for some
 * bizarre reason, is not exposed in the standard API.
 *
 * @author Tim Fennell
 */
class CustomGzipOutputStream extends GZIPOutputStream {
    CustomGzipOutputStream(final OutputStream outputStream, final int bufferSize, final int compressionLevel) throws IOException {
        super(outputStream, bufferSize);
        this.def.setLevel(compressionLevel);
    }
}

