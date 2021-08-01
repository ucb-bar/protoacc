
set -ex

STARTDIR=$(pwd)

# build the modified protobuf library
cd ../microbenchmarks
./build-protobuf-all.sh

cd $STARTDIR

# re-gen ubmarks
cd ../microbenchmarks
python gen-primitive-tests.py
rm -rf primitive-benchmarks/*.riscv
rm -rf primitive-benchmarks/*.x86
time make -f Makefile -j64 all

python gen-primitive-tests-serializer.py
rm -rf primitive-benchmarks-serializer/*.riscv
rm -rf primitive-benchmarks-serializer/*.x86
time make -f Makefile-serializer -j64 all

cd $STARTDIR
cd hyperproto/HyperProtoBench
bash ../buildall.sh
bash ../copy.sh $STARTDIR

# build images
cd $STARTDIR
cd boom-plain-bmarks && ./build.sh
cd ../protoacc-des-bmarks && ./build.sh
cd ../protoacc-ser-bmarks && ./build.sh


