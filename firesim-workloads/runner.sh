

function runN () {
    echo "running bench$1.x86"
    ./bench$1.x86 dowrite
    for i in {1..2}
    do
        ./bench$1.x86
    done
    echo "done bench$1.x86"
}

runN 0
runN 1
runN 2
runN 3
runN 4
runN 5

