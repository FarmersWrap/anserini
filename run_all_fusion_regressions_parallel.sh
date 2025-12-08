#!/bin/bash
#
# Script to run all fusion regression tests in parallel
# This script runs all BEIR fusion regression YAML files using run_fusion_regression.py
# Uses GNU parallel for parallel execution (if available) or runs sequentially
#

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FUSION_DIR="src/main/resources/fusion_regression"
PYTHON_SCRIPT="src/main/python/run_fusion_regression.py"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if parallel is available
if command -v parallel &> /dev/null; then
    USE_PARALLEL=true
    MAX_JOBS=${MAX_JOBS:-4}  # Default to 4 parallel jobs
else
    USE_PARALLEL=false
    echo -e "${YELLOW}Warning: GNU parallel not found. Running sequentially.${NC}"
    echo "Install with: sudo apt-get install parallel"
fi

# Count total files
TOTAL=$(ls -1 ${FUSION_DIR}/beir-v1.0.0-*.yaml 2>/dev/null | wc -l)
if [ "$TOTAL" -eq 0 ]; then
    echo -e "${RED}Error: No fusion regression YAML files found in ${FUSION_DIR}${NC}"
    exit 1
fi

echo "=========================================="
echo "Running all fusion regression tests"
echo "Total files: $TOTAL"
if [ "$USE_PARALLEL" = true ]; then
    echo "Parallel jobs: $MAX_JOBS"
fi
echo "=========================================="
echo

# Function to run a single regression test
run_regression() {
    local corpus=$1
    local log_file="${corpus}.log"
    
    if python "$PYTHON_SCRIPT" --regression "$corpus" > "$log_file" 2>&1; then
        echo -e "${GREEN}✓${NC} $corpus"
        return 0
    else
        echo -e "${RED}✗${NC} $corpus"
        return 1
    fi
}

export -f run_regression
export PYTHON_SCRIPT
export RED GREEN YELLOW NC

# Run all tests
if [ "$USE_PARALLEL" = true ]; then
    # Extract corpus names and run in parallel
    ls -1 ${FUSION_DIR}/beir-v1.0.0-*.yaml | \
        sed 's|.*/beir-v1.0.0-\(.*\)\.yaml|beir-v1.0.0-\1|' | \
        parallel -j "$MAX_JOBS" --tag run_regression {} | \
        sort
else
    # Sequential execution
    PASSED=0
    FAILED=0
    FAILED_CORPORA=()
    
    for yaml_file in ${FUSION_DIR}/beir-v1.0.0-*.yaml; do
        corpus=$(basename "$yaml_file" .yaml)
        echo -e "${YELLOW}[$((PASSED + FAILED + 1))/$TOTAL]${NC} $corpus"
        
        if run_regression "$corpus"; then
            ((PASSED++))
        else
            ((FAILED++))
            FAILED_CORPORA+=("$corpus")
        fi
    done
    
    # Summary
    echo
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
fi

echo
echo "=========================================="
echo "All tests completed!"
echo "=========================================="

