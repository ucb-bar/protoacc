
set -ex

echo "generating ubmark plots"
cd dataprocess/ubmarks
./regen-plots.sh
echo "generating bmark plots"
cd ../bmarks
./runall.sh
