#!/bin/bash
#
# Dry-run script to show all fusion regression commands that would be executed
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FUSION_DIR="src/main/resources/fusion_regression"
PYTHON_SCRIPT="src/main/python/run_fusion_regression.py"

echo "=========================================="
echo "Dry-run: Fusion regression commands"
echo "=========================================="
echo

for yaml_file in ${FUSION_DIR}/beir-v1.0.0-*.yaml; do
    corpus=$(basename "$yaml_file" .yaml)
    echo "python $PYTHON_SCRIPT --regression $corpus"
done

echo
echo "Total: $(ls -1 ${FUSION_DIR}/beir-v1.0.0-*.yaml 2>/dev/null | wc -l) files"

