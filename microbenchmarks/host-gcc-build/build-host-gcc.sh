#!/usr/bin/env bash

set -e

VERSION="gcc-9.2.0"

wget https://ftp.gnu.org/gnu/gcc/$VERSION/$VERSION.tar.gz

tar xzf $VERSION.tar.gz

cd $VERSION
./contrib/download_prerequisites
cd ..

mkdir -p $VERSION-build
cd $VERSION-build

mkdir -p $(pwd)/../../host-gcc-install
../$VERSION/configure --enable-languages=c,c++ --disable-multilib --prefix=$(pwd)/../../host-gcc-install

make -j32
make install
