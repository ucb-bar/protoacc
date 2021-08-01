


function doclean {
    cd ../$1
    make clean
}

function dobuild_both {
    cd ../$1
    make -j32
    cp benchmark.riscv $1.riscv
    cp benchmark.x86 $1.x86
}

function dobuild_rvonly {
    cd ../$1
    make -j32 benchmark.riscv
    cp benchmark.riscv $1.riscv
}

function dorun_both {
    cd ../$1
    ./$1.x86 dowrite
    spike pk $1.riscv dowrite
}

function dorun_rvonly {
    cd ../$1
    # always run all of them, even though they'll fail due to illegal inst
    spike pk $1.riscv || true
}


cd bench0
doclean bench0
doclean bench1
doclean bench2
doclean bench3
doclean bench4
doclean bench5

doclean bench0-deser
doclean bench1-deser
doclean bench2-deser
doclean bench3-deser
doclean bench4-deser
doclean bench5-deser

doclean bench0-ser
doclean bench1-ser
doclean bench2-ser
doclean bench3-ser
doclean bench4-ser
doclean bench5-ser



dobuild_both bench0 &
dobuild_both bench1 &
dobuild_both bench2 &
dobuild_both bench3 &
dobuild_both bench4 &
dobuild_both bench5 &

dobuild_rvonly bench0-deser &
dobuild_rvonly bench1-deser &
dobuild_rvonly bench2-deser &
dobuild_rvonly bench3-deser &
dobuild_rvonly bench4-deser &
dobuild_rvonly bench5-deser &

dobuild_rvonly bench0-ser &
dobuild_rvonly bench1-ser &
dobuild_rvonly bench2-ser &
dobuild_rvonly bench3-ser &
dobuild_rvonly bench4-ser &
dobuild_rvonly bench5-ser &


wait

dorun_both bench0
dorun_both bench1
dorun_both bench2
dorun_both bench3
dorun_both bench4
dorun_both bench5

dorun_rvonly bench0-deser
dorun_rvonly bench1-deser
dorun_rvonly bench2-deser
dorun_rvonly bench3-deser
dorun_rvonly bench4-deser
dorun_rvonly bench5-deser

dorun_rvonly bench0-ser
dorun_rvonly bench1-ser
dorun_rvonly bench2-ser
dorun_rvonly bench3-ser
dorun_rvonly bench4-ser
dorun_rvonly bench5-ser


