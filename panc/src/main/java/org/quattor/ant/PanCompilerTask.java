/*
 Copyright (c) 2006-2012 Centre National de la Recherche Scientifique (CNRS).

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.quattor.ant;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.DirSet;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;
import org.quattor.pan.Compiler;
import org.quattor.pan.CompilerLogging;
import org.quattor.pan.CompilerOptions;
import org.quattor.pan.CompilerResults;
import org.quattor.pan.exceptions.SyntaxException;
import org.quattor.pan.output.Formatter;
import org.quattor.pan.repository.SourceType;

/**
 * An ant task which permits calling the pan compiler from an ant build file.
 * This task allows all of the compiler parameters to be accessed and will
 * optionally check dependency files before starting a build. See individual
 * setter methods for the parameters which can be used in the build file.
 *
 * @author loomis
 *
 */
public class PanCompilerTask extends Task {

    /* List of files to actually compile and process. Should be object files! */
    private LinkedList<File> objectFiles = new LinkedList<File>();

    /* The root directory for the includes. */
    private File includeroot = null;

    /* A comma- or space-separated list of file globs. */
    private List<DirSet> includes = new LinkedList<DirSet>();

    /* The list of directories to include in search path. */
    private LinkedList<File> includeDirectories = new LinkedList<File>();

    private Pattern debugNsInclude = null;

    private Pattern debugNsExclude = null;

    private boolean debugTask = false;

    /* Produce very verbose output */
    private boolean debugVerbose = false;

    private Pattern ignoreDependencyPattern = null;

    private boolean checkDependencies = true;

    private boolean verbose = false;

    private int deprecationLevel = 0;

    private boolean failOnWarn = false;

    final protected static String debugIndent = "    ";

    private String logging = "none";

    private File logFile = null;

    private int batchSize = 0;

    private int nthread = 0;

    private int maxIteration = 10000;

    private int maxRecursion = 50;

    private File outputDir = null;

    private Set<Formatter> formatters;

    private String initialData;

    private CompilerOptions.DeprecationWarnings deprecationWarnings = null;

    public PanCompilerTask() {
        setFormats("pan,dep");
    }

    @Override
    public void execute() throws BuildException {

        // If some include globs were specified, then check that the
        // includeroot was specified. Add the necessary paths.
        if (includes.size() > 0) {
            if (includeroot == null) {
                throw new BuildException(
                        "includeroot must be specified to use 'includes' parameter");
            }

            Path antpath = new Path(getProject());

            for (DirSet dirset : includes) {
                dirset.setDir(includeroot);
                antpath.addDirset(dirset);
            }
            addPaths(antpath);
        }

        // This can be dropped when the old style parameters are removed.
        if (deprecationWarnings == null) {
            deprecationWarnings = CompilerOptions.getDeprecationWarnings(
                    deprecationLevel, failOnWarn);
        }

        // Collect the options for the compilation.
        CompilerOptions options = null;
        try {
            options = new CompilerOptions(debugNsInclude, debugNsExclude,
                    maxIteration, maxRecursion, formatters, outputDir, includeDirectories,
                    deprecationWarnings, null, null, initialData, nthread);
        } catch (SyntaxException e) {
            throw new BuildException("invalid root element: " + e.getMessage());
        }

        // If the debugging for the task is enabled, then print out the options
        // and the arguments.
        if (debugTask) {
            System.err.println(options);
        }
        if (debugVerbose) {
            System.err.println("includeroot: " + includeroot);
            System.err.println("Profiles to process : \n");
            for (File objectFile : objectFiles) {
                System.err.println(debugIndent + objectFile + "\n");
            }
        }

        // Determine what object files are outdated. Assume that all are, unless
        // the check is done.
        List<File> outdatedFiles = objectFiles;
        if (outputDir != null && checkDependencies) {

            DependencyChecker checker = new DependencyChecker(
                    includeDirectories, outputDir, formatters,
                    ignoreDependencyPattern);

            outdatedFiles = checker.filterForOutdatedFiles(objectFiles);

            if (debugVerbose) {
                System.err.println("Outdated profiles: \n");
                for (File objectFile : outdatedFiles) {
                    System.err.println(debugIndent + objectFile + "\n");
                }
            }

        }

        // Print out information on how many files will be processed.
        if (verbose) {
            System.out.println(outdatedFiles.size() + "/" + objectFiles.size()
                    + " template(s) being processed");
        }

        // Activate loggers if specified. If the logging is activated but there
        // is no log file, no output will be generated.
        CompilerLogging.activateLoggers(logging);
        CompilerLogging.setLogFile(logFile);

        // Batch the files to process, if requested.
        List<List<File>> batches = batchOutdatedFiles(outdatedFiles);

        boolean hadError = false;
        for (List<File> batch : batches) {

            CompilerResults results = Compiler.run(options, null, batch);

            boolean batchHadError = results.print(verbose);

            if (batchHadError) {
                hadError = true;
            }

        }

        // Stop build if there was an error.
        if (hadError) {
            throw new BuildException("Compilation failed; see messages.");
        }
    }

    /**
     * Set the directory to use for the include globs. This is required only if
     * the includes parameter is set.
     *
     * @param includeroot
     *            File giving the root directory for the include globs
     */
    public void setIncludeRoot(File includeroot) {

        this.includeroot = includeroot;

        if (!includeroot.exists()) {
            throw new BuildException("includeroot doesn't exist: "
                    + includeroot);
        }
        if (!includeroot.isDirectory()) {
            throw new BuildException("includeroot must be a directory: "
                    + includeroot);
        }
    }

    /**
     * Set the include globs to use for the pan compiler loadpath.
     *
     * @param includes
     *            String of comma- or space-separated file globs
     */
    public void setIncludes(String includes) {

        // Split the string into separate file globs.
        String[] globs = includes.split("[\\s,]+");

        // Loop over these globs and create dirsets from them.
        // Do not set the root directory until the task is
        // executed.
        for (String glob : globs) {
            DirSet dirset = new DirSet();
            dirset.setIncludes(glob);
            this.includes.add(dirset);
        }
    }

    /**
     * Support nested path elements. This is called by ant only after all of the
     * children of the path have been processed. These are the include
     * directories to find non-object templates. Non-directory elements will be
     * silently ignored.
     *
     * @param path
     *            a configured Path
     */
    public void addConfiguredPath(Path path) {
        if (path != null)
            addPaths(path);
    }

    /**
     * Collect all of the directories listed within enclosed path tags. Order of
     * the path elements is preserved. Duplicates are included where first
     * specified.
     *
     * @param p
     *            Path containing directories to include in compilation
     */
    private void addPaths(Path p) {

        for (String d : p.list()) {
            File dir = new File(d);
            if (dir.exists() && dir.isDirectory()) {
                if (!includeDirectories.contains(dir))
                    includeDirectories.add(dir);
            }
        }
    }

    /**
     * Support nested fileset elements. This is called by ant only after all of
     * the children of the fileset have been processed. Collect all of the
     * selected files from the fileset.
     *
     * @param fileset
     *            a configured FileSet
     */
    public void addConfiguredFileSet(FileSet fileset) {
        addFiles(fileset);
    }

    /**
     * Utility method that adds all of the files in a fileset to the list of
     * files to be processed. Duplicate files appear only once in the final
     * list. Files not ending with a valid source file extension are ignored.
     *
     * @param fs
     *            FileSet from which to get the file names
     */
    private void addFiles(FileSet fs) {

        // Get the files included in the fileset.
        DirectoryScanner ds = fs.getDirectoryScanner(getProject());

        // The base directory for all files.
        File basedir = ds.getBasedir();

        // Loop over each file creating a File object.
        for (String f : ds.getIncludedFiles()) {
            if (SourceType.hasSourceFileExtension(f)) {
                objectFiles.add(new File(basedir, f));
            }
        }
    }

    /**
     * Setting this flag will print debugging information from the task itself.
     * This is primarily useful if one wants to debug a build using the command
     * line interface.
     *
     * @param debugTask
     *            flag to print task debugging information
     */
    public void setDebugTask(int debugTask) {
        this.debugTask = (debugTask != 0);
        this.debugVerbose = (debugTask > 1);
    }

    /**
     * Set the regular expression used to include pan namespaces for debugging.
     *
     * @param pattern
     */
    public void setDebugNsInclude(String pattern) {
        debugNsInclude = Pattern.compile(pattern);
    }

    /**
     * Set the regular expression used to exclude pan namespaces for debugging.
     *
     * @param pattern
     */
    public void setDebugNsExclude(String pattern) {
        debugNsExclude = Pattern.compile(pattern);
    }

    /**
     * Provides a dict() with a data structure that will be used to initialize
     * all generated profiles.
     *
     * @param initialData
     */
    public void setInitialData(String initialData) {
        this.initialData = initialData;
    }

    /**
     * Set the output directory for generated machine profiles and dependency
     * files.
     *
     * @param outputDir
     *            directory for produced files
     */
    public void setOutputDir(File outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Defines the formatters used to generate the output files. The default
     * value is "pan,dep".
     *
     * @param fmts
     *            comma-separated list of formatters to use
     */
    public void setFormats(String fmts) {
        formatters = CompilerOptions.getFormatters(fmts);
    }

    /**
     * The pan compiler allows an iteration limit to be set to avoid infinite
     * loops. Non-positive values indicate that no limit should be used.
     *
     * @param maxIteration
     *            maximum number of permitted iterations
     */
    public void setMaxIteration(int maxIteration) {
        this.maxIteration = maxIteration;
    }

    /**
     * Sets the default maximum number of recursions.
     *
     * @param maxRecursion
     */
    public void setMaxRecursion(int maxRecursion) {
        this.maxRecursion = maxRecursion;
    }

    /**
     * Enable the given types of logging. Note that NONE will take precedence
     * over active logging flags and turn all logging off.
     *
     * @param loggingFlags
     *            a comma-separated list of logging types to enable
     */
    public void setLogging(String loggingFlags) {
        this.logging = loggingFlags;
    }

    /**
     * Set the log file to use for logging.
     *
     * @param logFile
     *            file to use for logging
     */
    public void setLogfile(File logFile) {
        this.logFile = logFile;
    }

    /**
     * Determines whether deprecation warnings are emitted and if so, whether to
     * treat them as fatal errors.
     *
     * @param warnings
     */
    public void setWarnings(String warnings) {
        try {
            deprecationWarnings = CompilerOptions.DeprecationWarnings
                    .fromString(warnings);
        } catch (IllegalArgumentException e) {
            throw new BuildException("invalid value for warnings: " + warnings);
        }
    }

    /**
     * Flag to indicate that extra information should be written to the standard
     * output. This gives the total number of files which will be processed and
     * statistics coming from the compilation.
     *
     * @param verbose
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * This any task can check machine profile dependencies to avoid processing
     * machine profiles which are already up-to-date. Setting this flag allows
     * the dependency checking to minimize the number of files which are built.
     *
     * @param checkDependencies
     */
    public void setCheckDependencies(boolean checkDependencies) {
        this.checkDependencies = checkDependencies;
    }

    /**
     * Dependencies that must be ignored when selecting the profiles to rebuild.
     * Value must be a regular expression matching a (namespaced) template name.
     *
     * NOTE: Use of this option may cause incomplete builds. Use this option
     * with extreme caution.
     *
     * @param ignoreDependencyPattern
     *            regular expression used to match namespaced template names to
     *            ignore
     */
    public void setIgnoreDependencyPattern(String ignoreDependencyPattern) {
        try {
            Pattern pattern = Pattern.compile(ignoreDependencyPattern);
            this.ignoreDependencyPattern = pattern;
        } catch (PatternSyntaxException e) {
            throw new BuildException("invalid ignore dependency pattern: "
                    + e.getMessage());
        }
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = (batchSize > 0) ? batchSize : 0;
    }

    public int getNthread() {
        return nthread;
    }

    public void setNthread(int nthread) {
        this.nthread = (nthread > 0) ? nthread : 0;
    }

    /**
     * This utility method will group the file into a set of equal sized batches
     * (except for possibly the last batch).
     *
     * @param outdatedFiles
     *
     * @return list of batched files
     */
    private List<List<File>> batchOutdatedFiles(List<File> outdatedFiles) {

        List<List<File>> batches = new LinkedList<List<File>>();

        int total = outdatedFiles.size();

        int myBatchSize = (batchSize <= 0) ? outdatedFiles.size() : batchSize;

        for (int start = 0; start < total; start += myBatchSize) {
            int end = Math.min(start + myBatchSize, total);
            batches.add(outdatedFiles.subList(start, end));
        }

        return batches;
    }

}
