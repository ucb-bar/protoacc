
set -ex

./x86-runs.sh

cd ../../../../../deploy

RELPATH="../target-design/chipyard/generators/protoacc/firesim-workloads/"

FSIMARGS="-b $RELPATH/config_build.ini -r $RELPATH/config_build_recipes.ini -a $RELPATH/config_hwdb.ini"


function fsimrun() {
    firesim -c $RELPATH/$1 $FSIMARGS launchrunfarm
    firesim -c $RELPATH/$1 $FSIMARGS infrasetup
    firesim -c $RELPATH/$1 $FSIMARGS runworkload
    firesim -c $RELPATH/$1 $FSIMARGS terminaterunfarm --forceterminate
}


fsimrun protoacc-des-bmarks-config-runtime.ini
#sleep 5
fsimrun protoacc-ser-bmarks-config-runtime.ini
#sleep 5
fsimrun boom-plain-bmarks-config-runtime.ini

#wait
