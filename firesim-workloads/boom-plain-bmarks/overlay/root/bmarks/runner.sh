

function runN () {
    echo "running bench$1"
    /root/bmarks/bench$1.riscv dowrite
    for i in {1..2}
    do
        /root/bmarks/bench$1.riscv
    done
    echo "done bench$1"
}

runN $1
