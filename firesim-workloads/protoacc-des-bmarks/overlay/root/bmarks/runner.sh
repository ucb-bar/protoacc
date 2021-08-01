

function runN () {
    echo "running bench$1"
    /root/bmarks/bench$1-deser.riscv dowrite
    for i in {1..2}
    do
        /root/bmarks/bench$1-deser.riscv
    done
    echo "done bench$1"
}

runN $1
