
set -e

BASEDIR=../../../../../../../deploy/results-workload/
HYPERPROTODIR=../../hyperproto/HyperProtoBench/

# pull data from the latest matching directories
DESDIRS=($BASEDIR/*-protoacc-des-bmarks*)
DESERDIR=${DESDIRS[-1]}

SERDIRS=($BASEDIR/*-protoacc-ser-bmarks*)
SERDIR=${SERDIRS[-1]}

PLAINDIRS=($BASEDIR/*-boom-plain-bmarks*)
PLAINDIR=${PLAINDIRS[-1]}

echo "using directories:"
echo $DESERDIR
echo $SERDIR
echo $PLAINDIR

DESEROUTFILE=deserresults
SEROUTFILE=serresults
PLAINOUTFILE=plainresults

function docomparedeser() {
    echo "diff bench $1"
    diff -q $DESERDIR/protoacc-des-bmarks-bench$1-des/outputdata $HYPERPROTODIR/bench$1/outputdata
}

function dodeserdiffs() {
    echo "deser diffs clean?"
    docomparedeser 0
    docomparedeser 1
    docomparedeser 2
    docomparedeser 3
    docomparedeser 4
    docomparedeser 5
}

function docompareser() {
    echo "diff bench $1"
    diff -q $SERDIR/protoacc-ser-bmarks-bench$1-ser/outputdata $HYPERPROTODIR/bench$1/outputdata
}

function doserdiffs() {
    echo "ser diffs clean?"
    docompareser 0
    docompareser 1
    docompareser 2
    docompareser 3
    docompareser 4
    docompareser 5
}

function dodesergrep () {
    echo "$1" >> $DESEROUTFILE
    grep "us des\|b, [0-9]" $DESERDIR/protoacc-des-bmarks-$1-des/uartlog >> $DESEROUTFILE
}

function dodeser () {
    echo "" > $DESEROUTFILE
    dodesergrep bench0
    dodesergrep bench1
    dodesergrep bench2
    dodesergrep bench3
    dodesergrep bench4
    dodesergrep bench5
}


function dosergrep () {
    echo "$1" >> $SEROUTFILE
    grep "us ser\|b, [0-9]" $SERDIR/protoacc-ser-bmarks-$1-ser/uartlog >> $SEROUTFILE
}

function doser () {
    echo "" > $SEROUTFILE
    dosergrep bench0
    dosergrep bench1
    dosergrep bench2
    dosergrep bench3
    dosergrep bench4
    dosergrep bench5
}

function doplaingrep () {
    echo "$1" >> $PLAINOUTFILE
    grep "us des\|us ser\|b, [0-9]" $PLAINDIR/boom-plain-bmarks-$1/uartlog >> $PLAINOUTFILE
}

function doplain () {
    echo "" > $PLAINOUTFILE
    doplaingrep bench0
    doplaingrep bench1
    doplaingrep bench2
    doplaingrep bench3
    doplaingrep bench4
    doplaingrep bench5
}

dodeser
doser
doplain

dodeserdiffs
doserdiffs
