
function copy_to_remoterun {
    mkdir -p remoterun
    cp $1/$1.x86 remoterun/
}


function copy_to_plain {
    cp $1/$1.riscv $2/boom-plain-bmarks/overlay/root/bmarks/
}

function copy_to_deser {
    cp $1/$1.riscv $2/protoacc-des-bmarks/overlay/root/bmarks/
}

function copy_to_ser {
    cp $1/$1.riscv $2/protoacc-ser-bmarks/overlay/root/bmarks/
}

copy_to_deser bench0-deser $1
copy_to_deser bench1-deser $1
copy_to_deser bench2-deser $1
copy_to_deser bench3-deser $1
copy_to_deser bench4-deser $1
copy_to_deser bench5-deser $1

copy_to_ser bench0-ser $1
copy_to_ser bench1-ser $1
copy_to_ser bench2-ser $1
copy_to_ser bench3-ser $1
copy_to_ser bench4-ser $1
copy_to_ser bench5-ser $1

copy_to_plain bench0 $1
copy_to_plain bench1 $1
copy_to_plain bench2 $1
copy_to_plain bench3 $1
copy_to_plain bench4 $1
copy_to_plain bench5 $1

copy_to_remoterun bench0
copy_to_remoterun bench1
copy_to_remoterun bench2
copy_to_remoterun bench3
copy_to_remoterun bench4
copy_to_remoterun bench5

