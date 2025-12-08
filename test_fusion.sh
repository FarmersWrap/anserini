export ANSERINI_JAR=$(ls target/anserini-*-fatjar.jar | head -1)
export OUTPUT_DIR="./runs"
CORPORA=(robust04 arguana); for c in "${CORPORA[@]}"
# do
#     echo $c
#     # bm 25
#     java -cp $ANSERINI_JAR --add-modules jdk.incubator.vector io.anserini.search.SearchCollection -index beir-v1.0.0-${c}.flat -topics beir-${c} -output $OUTPUT_DIR/run.inverted.beir-v1.0.0-${c}.flat.test.bm25 -bm25 -removeQuery -hits 1000

#     # bge 
#     java -cp $ANSERINI_JAR --add-modules jdk.incubator.vector io.anserini.search.SearchFlatDenseVectors -index beir-v1.0.0-${c}.bge-base-en-v1.5.flat -topics beir-${c} -encoder BgeBaseEn15 -output $OUTPUT_DIR/run.flat.beir-v1.0.0-${c}.bge-base-en-v1.5.test.bge-flat-onnx -hits 1000 -removeQuery -threads 16
    
#     # rrf fuse
#     java -cp $ANSERINI_JAR --add-modules jdk.incubator.vector io.anserini.fusion.FuseRuns -runs $OUTPUT_DIR/run.inverted.beir-v1.0.0-${c}.flat.test.bm25 $OUTPUT_DIR/run.flat.beir-v1.0.0-${c}.bge-base-en-v1.5.test.bge-flat-onnx -output $OUTPUT_DIR/runs.fuse.rrf.beir-v1.0.0-${c}.flat.bm25.bge-base-en-v1.5.bge-flat-onnx.topics.beir-v1.0.0-${c}.test.txt -method rrf -k 1000 -depth 1000 -rrf_k 60 -alpha 0.5

#     echo $c
#     echo rrf
#     java -cp $ANSERINI_JAR trec_eval -c -m ndcg_cut.10 qrels.beir-v1.0.0-${c}.test.txt $OUTPUT_DIR/runs.fuse.rrf.beir-v1.0.0-${c}.flat.bm25.bge-base-en-v1.5.bge-flat-onnx.topics.beir-v1.0.0-${c}.test.txt

# done