#!/bin/bash
#
# Script to run all fusion regression tests
# This script runs all BEIR fusion regression YAML files using run_fusion_regression.py
#

set -u  # Exit on undefined variable
set -o pipefail  # Exit on pipe failure

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FUSION_DIR="src/main/resources/fusion_regression"
PYTHON_SCRIPT="src/main/python/run_fusion_regression.py"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Count total files
TOTAL=$(ls -1 ${FUSION_DIR}/beir-v1.0.0-*.yaml 2>/dev/null | wc -l)
if [ "$TOTAL" -eq 0 ]; then
    echo -e "${RED}Error: No fusion regression YAML files found in ${FUSION_DIR}${NC}"
    exit 1
fi

echo "=========================================="
echo "Running all fusion regression tests"
echo "Total files: $TOTAL"
echo "=========================================="
echo

PASSED=0
FAILED=0
FAILED_CORPORA=()

# Get list of all BEIR corpora YAML files
for yaml_file in ${FUSION_DIR}/beir-v1.0.0-*.yaml; do
    # Extract corpus name from filename (e.g., beir-v1.0.0-nfcorpus.yaml -> beir-v1.0.0-nfcorpus)
    corpus=$(basename "$yaml_file" .yaml)
    
    echo -e "${YELLOW}[$((PASSED + FAILED + 1))/$TOTAL]${NC} Running: $corpus"
    
    # Run the regression test
    if python "$PYTHON_SCRIPT" --regression "$corpus" > "${corpus}.log" 2>&1; then
        echo -e "${GREEN}✓ PASSED${NC}: $corpus"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}✗ FAILED${NC}: $corpus (see ${corpus}.log)"
        FAILED=$((FAILED + 1))
        FAILED_CORPORA+=("$corpus")
    fi
    echo
done

# Summary
echo "=========================================="
echo "Summary"
echo "=========================================="
echo -e "Total:  $TOTAL"
echo -e "${GREEN}Passed: $PASSED${NC}"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED${NC}"
    echo
    echo "Failed corpora:"
    for corpus in "${FAILED_CORPORA[@]}"; do
        echo -e "  ${RED}✗${NC} $corpus (see ${corpus}.log)"
    done
else
    echo -e "${GREEN}Failed: 0${NC}"
fi
echo

# Exit with error code if any tests failed
if [ $FAILED -gt 0 ]; then
    exit 1
else
    exit 0
fi

