#!/system/bin/sh

DIR=$1

cd $DIR
caddy &
echo "$!" > caddy.pid
