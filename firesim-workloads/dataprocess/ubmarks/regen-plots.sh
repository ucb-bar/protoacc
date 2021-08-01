
set -e
set -x

./copy-results.sh
python process.py > process.py.log
python process-serialize.py > process-serialize.py.log

