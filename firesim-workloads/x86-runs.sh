
set -e

SSHOPTS="-o StrictHostKeyChecking=no"

THISDIR=$(pwd)
IPADDRFILE=$(pwd)/ipaddr

AWSTOOLSDIR=../../../../../deploy/awstools

cd $AWSTOOLSDIR

python awstools.py benchlaunch 2>&1 | tee $IPADDRFILE
cd $THISDIR

# wait for instance boot
sleep 5m


IPADDR=$(grep -E -o "192\.168\.[0-9]{1,3}\.[0-9]{1,3}" $IPADDRFILE | head -n 1)

echo $IPADDR

scp $SSHOPTS -r hyperproto/HyperProtoBench/remoterun $IPADDR:
scp $SSHOPTS -r runner.sh $IPADDR:remoterun/
ssh $SSHOPTS $IPADDR "cd remoterun && ./runner.sh" 2>&1 | tee hyper-remoterun
mv hyper-remoterun dataprocess/bmarks/x86results


cp ../microbenchmarks/testsfrag.mk des-primitive-tests.sh
sed -i '/riscv/d' des-primitive-tests.sh
sed -i '/ = /d' des-primitive-tests.sh
sed -i 's/primitive-tests/\.\/primitive-tests/g' des-primitive-tests.sh
sed -i 's/ \\//g' des-primitive-tests.sh
scp $SSHOPTS des-primitive-tests.sh $IPADDR:
scp $SSHOPTS -r ../microbenchmarks/primitive-tests $IPADDR:
ssh $SSHOPTS $IPADDR "bash des-primitive-tests.sh" 2>&1 | tee x86-deser-remoterun
mv x86-deser-remoterun dataprocess/ubmarks/x86

cp ../microbenchmarks/testsfrag-serializer.mk ser-primitive-tests.sh
sed -i '/riscv/d' ser-primitive-tests.sh
sed -i '/ = /d' ser-primitive-tests.sh
sed -i 's/primitive-tests/\.\/primitive-tests/g' ser-primitive-tests.sh
sed -i 's/ \\//g' ser-primitive-tests.sh
scp $SSHOPTS ser-primitive-tests.sh $IPADDR:
scp $SSHOPTS -r ../microbenchmarks/primitive-tests-serializer $IPADDR:
ssh $SSHOPTS $IPADDR "bash ser-primitive-tests.sh" 2>&1 | tee x86-ser-remoterun
mv x86-ser-remoterun dataprocess/ubmarks/x86-serialize

cd $AWSTOOLSDIR
python awstools.py benchterminate


