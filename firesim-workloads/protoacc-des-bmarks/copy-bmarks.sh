
set -ex

cp ../../microbenchmarks/primitive-tests/*.riscv overlay/root/ubmarks/
cp ../../microbenchmarks/testsfrag.mk overlay/root/ubmarks/

sed -i '/x86/d' overlay/root/ubmarks/testsfrag.mk
sed -i '/ = /d' overlay/root/ubmarks/testsfrag.mk
sed -i 's/primitive-tests/\/root\/ubmarks/g' overlay/root/ubmarks/testsfrag.mk
sed -i 's/ \\//g' overlay/root/ubmarks/testsfrag.mk
sed -i 's/riscv/log/g' overlay/root/ubmarks/testsfrag.mk
sort overlay/root/ubmarks/testsfrag.mk > overlay/root/ubmarks/testsfrag2.mk
mv overlay/root/ubmarks/testsfrag2.mk overlay/root/ubmarks/testsfrag.mk
sed -i 's/log/riscv/g' overlay/root/ubmarks/testsfrag.mk

mv overlay/root/ubmarks/testsfrag.mk overlay/root/ubmarks/run-all.sh
chmod +x overlay/root/ubmarks/run-all.sh
