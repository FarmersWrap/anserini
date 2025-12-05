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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.args4j.Option;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.anserini.search.ScoredDocs;

/**
 * Main logic class for Fusion
 */
public class RunsFuser {
  private final Args args;

  private static final String METHOD_RRF = "rrf";
  private static final String METHOD_INTERPOLATION = "interpolation";
  private static final String METHOD_AVERAGE = "average";
  private static final String METHOD_WEIGHTED = "weighted";
  private static final String METHOD_DYNAMIC = "dynamic";

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

    @Option(name = "-weights", metaVar = "[weights]", required = false, usage = "Comma-separated weights for weighted fusion (e.g., \"0.7,0.3\")")
    public String weights = null;

    @Option(name = "-min_max_normalization", required = false, usage = "Apply min-max normalization before fusion")
    public boolean minMaxNormalization = false;

    @Option(name = "-lambda_file", metaVar = "[file]", required = false, usage = "Path to JSONL file containing per-query lambda values for dynamic fusion")
    public String lambdaFile = null;
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
    for (ScoredDocs run : runs) {
      ScoredDocsFuser.rescore(ScoredDocsFuser.RescoreMethod.SCALE, 0, (1/(double)runs.size()), run);
    }

    return ScoredDocsFuser.merge(runs, depth, k);
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
    for (ScoredDocs run : runs) {
      ScoredDocsFuser.rescore(ScoredDocsFuser.RescoreMethod.RRF, rrf_k, 0, run);
    }

    return ScoredDocsFuser.merge(runs, depth, k);
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
    ScoredDocsFuser.rescore(ScoredDocsFuser.RescoreMethod.SCALE, 0, alpha, runs.get(0));
    ScoredDocsFuser.rescore(ScoredDocsFuser.RescoreMethod.SCALE, 0, 1 - alpha, runs.get(1));

    return ScoredDocsFuser.merge(runs, depth, k);
  }

  /**
   * Perform fusion by weighted scaling on a list of ScoredDocs objects.
   * Each run is scaled by its corresponding weight before merging.
   *
   * @param runs List of ScoredDocs objects.
   * @param weights List of weight values. Must have the same number of weights as runs.
   * @param depth Maximum number of results from each input run to consider. Set to Integer.MAX_VALUE by default, which indicates that the complete list of results is considered.
   * @param k Length of final results list. Set to Integer.MAX_VALUE by default, which indicates that the union of all input documents are ranked.
   * @return Output ScoredDocs that combines input runs via weighted scaling.
   */
  public static ScoredDocs weighted(List<ScoredDocs> runs, List<Double> weights, int depth, int k) {
    for (int i = 0; i < runs.size(); i++) {
      ScoredDocsFuser.rescore(ScoredDocsFuser.RescoreMethod.SCALE, 0, weights.get(i), runs.get(i));
    }

    return ScoredDocsFuser.merge(runs, depth, k);
  }

  /**
   * Perform dynamic fusion by interpolation on a list of exactly two ScoredDocs objects.
   * Uses per-query lambda values from a map.
   * new_score = first_run_score * lambda + (1 - lambda) * second_run_score.
   * For queries not in the lambda map, uses defaultLambda.
   *
   * @param runs List of ScoredDocs objects. Exactly two runs.
   * @param lambdaMap Map from query_id to lambda value for per-query interpolation.
   * @param defaultLambda Default lambda value for queries not in the map.
   * @param depth Maximum number of results from each input run to consider.
   * @param k Length of final results list.
   * @return Output ScoredDocs that combines input runs via dynamic interpolation.
   */
  public static ScoredDocs dynamicInterpolation(List<ScoredDocs> runs, Map<String, Double> lambdaMap, 
                                                double defaultLambda, int depth, int k) {
    // Apply per-query scaling to first run (dense) with lambda
    ScoredDocsFuser.rescorePerQuery(ScoredDocsFuser.RescoreMethod.SCALE, 0, lambdaMap, defaultLambda, runs.get(0));
    
    // Apply per-query scaling to second run (sparse) with (1 - lambda)
    Map<String, Double> oneMinusLambdaMap = new HashMap<>();
    for (Map.Entry<String, Double> entry : lambdaMap.entrySet()) {
      oneMinusLambdaMap.put(entry.getKey(), 1.0 - entry.getValue());
    }
    double defaultOneMinusLambda = 1.0 - defaultLambda;
    ScoredDocsFuser.rescorePerQuery(ScoredDocsFuser.RescoreMethod.SCALE, 0, oneMinusLambdaMap, defaultOneMinusLambda, runs.get(1));

    return ScoredDocsFuser.merge(runs, depth, k);
  }

  /**
   * Apply min-max normalization to runs if requested.
   *
   * @param runs List of ScoredDocs objects to normalize.
   */
  private void normalizeRuns(List<ScoredDocs> runs) {
    if (args.minMaxNormalization) {
      for (ScoredDocs run : runs) {
        ScoredDocsFuser.normalizeScores(run);
      }
    }
  }

  /**
   * Parse comma-separated weights string into a list of doubles.
   *
   * @param weightsStr Comma-separated weights string (e.g., "0.7,0.3")
   * @return List of weight values
   */
  private static List<Double> parseWeights(String weightsStr) {
    List<Double> weights = new ArrayList<>();
    String[] parts = weightsStr.split(",");
    for (String part : parts) {
      try {
        weights.add(Double.parseDouble(part.trim()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid weight value: " + part + ". Weights must be numeric.");
      }
    }
    return weights;
  }

  /**
   * Read and parse JSONL file containing per-query lambda values.
   * Expected format: {"query_id": "PLAIN-1008", "lambda": 0.3, ...}
   *
   * @param lambdaFilePath Path to the JSONL file
   * @param defaultLambda Default lambda value to use if query_id is not found
   * @return Map from query_id to lambda value
   * @throws IOException If the file cannot be read
   */
  private static Map<String, Double> loadLambdaMap(String lambdaFilePath, double defaultLambda) throws IOException {
    Map<String, Double> lambdaMap = new HashMap<>();
    ObjectMapper mapper = new ObjectMapper();
    
    try (BufferedReader br = new BufferedReader(new FileReader(lambdaFilePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        try {
          JsonNode json = mapper.readTree(line);
          String queryId = json.get("query_id").asText();
          double lambda = json.get("lambda").asDouble();
          lambdaMap.put(queryId, lambda);
        } catch (Exception e) {
          throw new IOException("Error parsing JSON line in lambda file: " + line + ". Error: " + e.getMessage());
        }
      }
    }
    
    return lambdaMap;
  }

  /**
   * Validate inputs and parse weights/lambda map for fusion methods.
   *
   * @param runs List of ScoredDocs objects.
   * @return Parsed weights list for weighted fusion, lambda map for dynamic fusion, null for other methods.
   * @throws IllegalArgumentException If validation fails.
   * @throws IOException If lambda file cannot be read.
   */
  private Object validateAndParse(List<ScoredDocs> runs) throws IOException {
    switch (args.method.toLowerCase()) {
      case METHOD_RRF:
        break;
      case METHOD_AVERAGE:
          break;
      case METHOD_INTERPOLATION:
        if (runs.size() != 2) {
          throw new IllegalArgumentException("Interpolation requires exactly 2 runs");
        }
        break;
      case METHOD_WEIGHTED:
        if (args.weights == null || args.weights.isEmpty()) {
          throw new IllegalArgumentException("Weights must be provided for weighted fusion method");
        }
        List<Double> weights = parseWeights(args.weights);
        if (runs.size() != weights.size()) {
          throw new IllegalArgumentException("Number of runs must match number of weights");
        }
        return weights;
      case METHOD_DYNAMIC:
        if (runs.size() != 2) {
          throw new IllegalArgumentException("Dynamic fusion requires exactly 2 runs");
        }
        if (args.lambdaFile == null || args.lambdaFile.isEmpty()) {
          throw new IllegalArgumentException("Lambda file must be provided for dynamic fusion method");
        }
        Map<String, Double> lambdaMap = loadLambdaMap(args.lambdaFile, args.alpha);
        return lambdaMap;
      default:
        return null;
    }
    return null;
  }

  /**
   * Process the fusion of ScoredDocs objects based on the specified method.
   *
   * @param runs List of ScoredDocs objects to be fused.
   * @throws IOException If an I/O error occurs while saving the output.
   */
  @SuppressWarnings("unchecked")
  public void fuse(List<ScoredDocs> runs) throws IOException {
    // Validate inputs and parse weights/lambda map if needed
    Object parsedData = validateAndParse(runs);
    
    // Apply min-max normalization if requested
    normalizeRuns(runs);
    
    ScoredDocs fusedRun;

    // Select fusion method and perform fusion
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
      case METHOD_WEIGHTED:
        fusedRun = weighted(runs, (List<Double>) parsedData, args.depth, args.k);
        break;
      case METHOD_DYNAMIC:
        Map<String, Double> lambdaMap = (Map<String, Double>) parsedData;
        fusedRun = dynamicInterpolation(runs, lambdaMap, args.alpha, args.depth, args.k);
        break;
      default:
        throw new IllegalArgumentException("Unknown fusion method: " + args.method + ". Supported methods are: average, rrf, interpolation, weighted, dynamic.");
    }

    Path outputPath = Paths.get(args.output);
    ScoredDocsFuser.saveToTxt(outputPath, args.runtag, fusedRun);
  }
}

