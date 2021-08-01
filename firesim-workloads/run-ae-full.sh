
set -ex

# generate all software components
./build-all-sw.sh

# generate host-side firesim driver
./build-driver-only.sh

# run all simulations
./sims-run-all.sh

# plot results from sim runs
./gen-all-plots.sh

echo "run-ae-full.sh complete."
