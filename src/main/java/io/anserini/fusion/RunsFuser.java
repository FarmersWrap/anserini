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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.Option;

import io.anserini.search.ScoredDocs;

/**
 * Main logic class for Fusion
 */
public class RunsFuser {
  private static final Logger LOG = LogManager.getLogger(RunsFuser.class);
  private final Args args;

  private static final String METHOD_RRF = "rrf";
  private static final String METHOD_INTERPOLATION = "interpolation";
  private static final String METHOD_AVERAGE = "average";
  private static final String METHOD_NORMALIZE = "normalize";

  public static class Args {
    @Option(name = "-output", metaVar = "[output]", required = true, usage = "Path to save the output")
    public String output;

    @Option(name = "-runtag", metaVar = "[runtag]", required = false, usage = "Run tag for the fusion")
    public String runtag = "anserini.fusion";

    @Option(name = "-method", metaVar = "[method]", required = false, usage = "Specify fusion method")
    public String method = "rrf";

    @Option(name = "-rrf_k", metaVar = "[number]", required = false, usage = "Parameter k needed for reciprocal rank fusion.")
    public int rrf_k = 60;

    @Option(name = "-alpha", metaVar = "[value]", required = false, usage = "Alpha value used for interpolation.")
    public double alpha = 0.5;

    @Option(name = "-k", metaVar = "[number]", required = false, usage = "number of documents to output for topic")
    public int k = 1000;

    @Option(name = "-depth", metaVar = "[number]", required = false, usage = "Pool depth per topic.")
    public int depth = 1000;
  }

  public RunsFuser(Args args) {
    this.args = args;
  }
  
  /**
   * Perform fusion by averaging on a list of ScoredDocs objects.
   *
   * @param runs List of ScoredDocs objects.
   * @param depth Maximum number of results from each input run to consider. Set to Integer.MAX_VALUE by default, which indicates that the complete list of results is considered.
   * @param k Length of final results list. Set to Integer.MAX_VALUE by default, which indicates that the union of all input documents are ranked.
   * @return Output ScoredDocs that combines input runs via averaging.
   */
  public static ScoredDocs average(List<ScoredDocs> runs, int depth, int k) {
    long rescoreStart = System.currentTimeMillis();
    for (ScoredDocs run : runs) {
      ScoredDocsFuser.rescore(RescoreMethod.SCALE, 0, (1/(double)runs.size()), run);
    }
    long rescoreEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Average rescoring: %.3f seconds", (rescoreEnd - rescoreStart) / 1000.0));

    long mergeStart = System.currentTimeMillis();
    ScoredDocs result = ScoredDocsFuser.merge(runs, depth, k);
    long mergeEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Average merging: %.3f seconds", (mergeEnd - mergeStart) / 1000.0));
    
    return result;
  }

  /**
   * Perform reciprocal rank fusion on a list of TrecRun objects. Implementation follows Cormack et al.
   * (SIGIR 2009) paper titled "Reciprocal Rank Fusion Outperforms Condorcet and Individual Rank Learning Methods."
   *
   * @param runs List of ScoredDocs objects.
   * @param rrf_k Parameter to avoid vanishing importance of lower-ranked documents. Note that this is different from the *k* in top *k* retrieval; set to 60 by default, per Cormack et al.
   * @param depth Maximum number of results from each input run to consider. Set to Integer.MAX_VALUE by default, which indicates that the complete list of results is considered.
   * @param k Length of final results list. Set to Integer.MAX_VALUE by default, which indicates that the union of all input documents are ranked.
   * @return Output ScoredDocs that combines input runs via reciprocal rank fusion.
   */
  public static ScoredDocs reciprocalRankFusion(List<ScoredDocs> runs, int rrf_k, int depth, int k) {
    long rescoreStart = System.currentTimeMillis();
    for (ScoredDocs run : runs) {
      ScoredDocsFuser.rescore(RescoreMethod.RRF, rrf_k, 0, run);
    }
    long rescoreEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] RRF rescoring: %.3f seconds", (rescoreEnd - rescoreStart) / 1000.0));

    long mergeStart = System.currentTimeMillis();
    ScoredDocs result = ScoredDocsFuser.merge(runs, depth, k);
    long mergeEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] RRF merging: %.3f seconds", (mergeEnd - mergeStart) / 1000.0));
    
    return result;
  }

  /**
   * Perform fusion by normalizing scores and taking the average. 
   *
   * @param runs List of ScoredDocs objects.
   * @param depth Maximum number of results from each input run to consider. Set to Integer.MAX_VALUE by default, which indicates that the complete list of results is considered.
   * @param k Length of final results list. Set to Integer.MAX_VALUE by default, which indicates that the union of all input documents are ranked.
   * @return Output ScoredDocs that combines input runs via reciprocal rank fusion.
   */
  public static ScoredDocs normalize(List<ScoredDocs> runs, int depth, int k) {
    long rescoreStart = System.currentTimeMillis();
    for (ScoredDocs run : runs) {
      ScoredDocsFuser.rescore(RescoreMethod.NORMALIZE, 0, 0, run);
    }
    long rescoreEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Normalize rescoring: %.3f seconds", (rescoreEnd - rescoreStart) / 1000.0));

    return average(runs, depth, k);
  }

  /**
   * Perform fusion by interpolation on a list of exactly two ScoredDocs objects.
   * new_score = first_run_score * alpha + (1 - alpha) * second_run_score.
   *
   * @param runs List of ScoredDocs objects. Exactly two runs.
   * @param alpha Parameter alpha will be applied on the first run and (1 - alpha) will be applied on the second run.
   * @param depth Maximum number of results from each input run to consider. Set to Integer.MAX_VALUE by default, which indicates that the complete list of results is considered.
   * @param k Length of final results list. Set to Integer.MAX_VALUE by default, which indicates that the union of all input documents are ranked.
   * @return Output ScoredDocs that combines input runs via interpolation.
   */  
  public static ScoredDocs interpolation(List<ScoredDocs> runs, double alpha, int depth, int k) {
    // Ensure exactly 2 runs are provided, as interpolation requires 2 runs
    if (runs.size() != 2) {
      throw new IllegalArgumentException("Interpolation requires exactly 2 runs");
    }

    long rescoreStart = System.currentTimeMillis();
    ScoredDocsFuser.rescore(RescoreMethod.SCALE, 0, alpha, runs.get(0));
    ScoredDocsFuser.rescore(RescoreMethod.SCALE, 0, 1 - alpha, runs.get(1));
    long rescoreEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Interpolation rescoring: %.3f seconds", (rescoreEnd - rescoreStart) / 1000.0));

    long mergeStart = System.currentTimeMillis();
    ScoredDocs result = ScoredDocsFuser.merge(runs, depth, k);
    long mergeEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Interpolation merging: %.3f seconds", (mergeEnd - mergeStart) / 1000.0));
    
    return result;
  }

  /**
   * Process the fusion of ScoredDocs objects based on the specified method.
   *
   * @param runs List of ScoredDocs objects to be fused.
   * @throws IOException If an I/O error occurs while saving the output.
   */
  public void fuse(List<ScoredDocs> runs) throws IOException {
    long startTime = System.currentTimeMillis();
    long checkpointTime = startTime;
    ScoredDocs fusedRun;

    LOG.info(String.format("[TIMING] Starting %s fusion method", args.method));
    
    // Select fusion method
    long methodStart = System.currentTimeMillis();
    switch (args.method.toLowerCase()) {
      case METHOD_RRF:
        fusedRun = reciprocalRankFusion(runs, args.rrf_k, args.depth, args.k);
        break;
      case METHOD_INTERPOLATION:
        fusedRun = interpolation(runs, args.alpha, args.depth, args.k);
        break;
      case METHOD_AVERAGE:
        fusedRun = average(runs, args.depth, args.k);
        break;
      case METHOD_NORMALIZE:
        fusedRun = normalize(runs, args.depth, args.k);
        break;
      default:
        throw new IllegalArgumentException("Unknown fusion method: " + args.method + 
            ". Supported methods are: average, rrf, interpolation.");
    }
    long methodEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] %s fusion computation: %.3f seconds", 
        args.method, (methodEnd - methodStart) / 1000.0));
    checkpointTime = methodEnd;

    LOG.info("[TIMING] Saving output...");
    long saveStart = System.currentTimeMillis();
    Path outputPath = Paths.get(args.output);
    ScoredDocsFuser.saveToTxt(outputPath, args.runtag,  fusedRun);
    long saveEnd = System.currentTimeMillis();
    LOG.info(String.format("[TIMING] Saving output: %.3f seconds", (saveEnd - saveStart) / 1000.0));
    
    LOG.info(String.format("[TIMING] Total fusion time: %.3f seconds", (saveEnd - startTime) / 1000.0));
  }
}
