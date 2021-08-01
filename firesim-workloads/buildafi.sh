set -ex

cd ../../../../../deploy

RELPATH="../target-design/chipyard/generators/protoacc/firesim-workloads/"

FSIMARGS="-b $RELPATH/config_build.ini -r $RELPATH/config_build_recipes.ini -a $RELPATH/config_hwdb.ini"

firesim -c $RELPATH/boom-plain-bmarks-config-runtime.ini $FSIMARGS buildafi

