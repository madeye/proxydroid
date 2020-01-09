#!/system/bin/sh

DIR=$1
SRC=$2
DST=$3

$DIR/gost $SRC $DST &
echo "$!" > $DIR/gost.pid