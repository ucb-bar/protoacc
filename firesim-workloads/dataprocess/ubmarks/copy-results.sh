
BASEDIR=../../../../../../../deploy/results-workload/

# pull data from the latest matching directories
DESDIRS=($BASEDIR/*-protoacc-des-bmarks*)
DESDIR=${DESDIRS[-1]}

SERDIRS=($BASEDIR/*-protoacc-ser-bmarks*)
SERDIR=${SERDIRS[-1]}

echo "using directories:"
echo $DESDIR
echo $SERDIR

cp $DESDIR/protoacc-des-bmarks-ubmarks-des/uartlog riscv-boom
cp $SERDIR/protoacc-ser-bmarks-ubmarks-ser/uartlog riscv-boom-serialize

sed -i 's///g' riscv-boom
sed -i 's///g' riscv-boom-serialize

