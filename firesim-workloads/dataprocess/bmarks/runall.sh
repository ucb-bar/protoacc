
set -e

bash collect-results.sh 2>&1 | tee HOWCOLLECT
python proc.py 2>&1 | tee FINAL_PROCESSED
python final_process.py 2>&1 | tee SPEEDUPS
