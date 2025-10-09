/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.fusion;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import io.anserini.search.ScoredDocs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for Fusion.
 */
public class FuseRuns {
  private static final Logger LOG = LogManager.getLogger(FuseRuns.class);

  public static class Args extends RunsFuser.Args {
    @Option(name = "-options", required = false, usage = "Print information about options.")
    public Boolean options = false;

    @Option(name = "-runs", handler = StringArrayOptionHandler.class, metaVar = "[file]", required = true, 
        usage = "Path to both run files to fuse")
    public String[] runs;

    @Option (name = "-resort", required = false, metaVar = "[flag]", usage="We Resort the Trec run files or not")
    public boolean resort = false;
  }

  private final Args args;
  private final RunsFuser fuser;
  private final List<ScoredDocs> runs = new ArrayList<ScoredDocs>();

  public FuseRuns(Args args) throws IOException {
    long startTime = System.currentTimeMillis();
    long checkpointTime = startTime;
    
    this.args = args;
    this.fuser = new RunsFuser(args);

    LOG.info(String.format("============ Initializing %s ============", FuseRuns.class.getSimpleName()));
    LOG.info("Runs: " + Arrays.toString(args.runs));
    LOG.info("Run tag: " + args.runtag);
    LOG.info("Fusion method: " + args.method);
    LOG.info("Reciprocal Rank Fusion K value (rrf_k): " + args.rrf_k);
    LOG.info("Alpha value for interpolation: " + args.alpha);
    LOG.info("Max documents to output (k): " + args.k);
    LOG.info("Pool depth: " + args.depth);
    LOG.info("Resort TREC run files: " + args.resort);

    long initTime = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Initialization: %.3f seconds", (initTime - checkpointTime) / 1000.0));
    checkpointTime = initTime;

    try {
      // Ensure positive depth and k values
      if (args.depth <= 0) {
        throw new IllegalArgumentException("Option depth must be greater than 0");
      }
      if (args.k <= 0) {
        throw new IllegalArgumentException("Option k must be greater than 0");
      }
    } catch (Exception e) {
      throw new IllegalArgumentException(String.format("Error: %s. Please check the provided arguments. Use the \"-options\" flag to print out detailed information about available options and their usage.\n",
        e.getMessage()));
    }

    long validationTime = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Validation: %.3f seconds", (validationTime - checkpointTime) / 1000.0));
    checkpointTime = validationTime;

    LOG.info("[TIMING] Loading run files...");
    for (int i = 0; i < args.runs.length; i++) {
      String runFile = args.runs[i];
      try {
        long loadStart = System.currentTimeMillis();
        Path path = Paths.get(runFile);
        ScoredDocs run = ScoredDocsFuser.readRun(path, args.resort);
        runs.add(run);
        long loadEnd = System.currentTimeMillis();
        LOG.info(String.format("[TIMING] Loading run file %d/%d (%s): %.3f seconds", 
            i + 1, args.runs.length, runFile, (loadEnd - loadStart) / 1000.0));
      } catch (Exception e) {
        throw new IllegalArgumentException(String.format("Error: %s. Please check the provided arguments. Use the \"-options\" flag to print out detailed information about available options and their usage.\n",
          e.getMessage()));
      }
    }
    
    long totalLoadTime = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Total run file loading: %.3f seconds", (totalLoadTime - checkpointTime) / 1000.0));
    LOG.info(String.format("[TIMING] Total initialization time: %.3f seconds", (totalLoadTime - startTime) / 1000.0));
  }

  public void run() throws IOException {
    LOG.info("============ Launching Fusion ============");
    long fusionStart = System.currentTimeMillis();
    fuser.fuse(runs);
    long fusionEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Fusion execution: %.3f seconds", (fusionEnd - fusionStart) / 1000.0));
  }

  public static void main(String[] args) throws Exception {
    Args fuseArgs = new Args();
    CmdLineParser parser = new CmdLineParser(fuseArgs, ParserProperties.defaults().withUsageWidth(120));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      if (fuseArgs.options) {
        System.err.printf("Options for %s:\n\n", FuseRuns.class.getSimpleName());
        parser.printUsage(System.err);
        ArrayList<String> required = new ArrayList<>();
        parser.getOptions().forEach(option -> {
          if (option.option.required()) {
            required.add(option.option.toString());
          }
        });
        System.err.printf("\nRequired options are %s\n", required);
      } else {
        System.err.printf("Error: %s. For help, use \"-options\" to print out information about options.\n",
          e.getMessage());       
      }
      return;
    }

    try {
      FuseRuns fuser = new FuseRuns(fuseArgs);
      fuser.run();
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }
}
