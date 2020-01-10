#!/system/bin/sh

DIR=$1
SRC=$2
DST=$3

PATH=$DIR:$PATH

gost $SRC $DST &> $DIR/gost.log &
echo "$!" > $DIR/gost.pid