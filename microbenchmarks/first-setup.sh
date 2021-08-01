#!/usr/bin/env bash

set -e

STARTDIR=$(pwd)

# make sure we have the right host gcc:
cd host-gcc-build
./build-host-gcc.sh

sudo yum -y install glibc-static
sudo pip install scipy
