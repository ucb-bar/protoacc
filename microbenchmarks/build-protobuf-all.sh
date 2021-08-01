#!/usr/bin/env bash

set -e

STARTDIR=$(pwd)

cd host-gcc-install
source sourceme
cd $STARTDIR

echo $PATH
echo $LD_LIBRARY_PATH

which g++
g++ --version


X86INSTALLDIR=$(pwd)/protobuf-x86-install
RISCVINSTALLDIR=$(pwd)/protobuf-riscv-install

PROTOBUFREPO=$(pwd)/protobuf

# use _build/ since protobuf repo already gitignores it
X86BUILDDIR=$PROTOBUFREPO/_build/build-x86
RISCVBUILDDIR=$PROTOBUFREPO/_build/build-riscv

# build protobuf for host system
mkdir -p $X86INSTALLDIR
mkdir -p $RISCVINSTALLDIR

mkdir -p $X86BUILDDIR
mkdir -p $RISCVBUILDDIR

cd $PROTOBUFREPO
git submodule update --init --recursive
./autogen.sh

cd $X86BUILDDIR
make clean || true
../../configure --prefix=$X86INSTALLDIR --disable-shared
make -j32
make install

cd $RISCVBUILDDIR
make clean || true
../../configure --prefix=$RISCVINSTALLDIR --with-protoc=$X86INSTALLDIR/bin/protoc --host=riscv64-unknown-linux-gnu CC=riscv64-unknown-linux-gnu-gcc CXX=riscv64-unknown-linux-gnu-g++ --disable-shared
make -j32
make install

