#!/system/bin/sh

DIR=/data/data/org.proxydroid
type=$2
host=$3
port=$4
auth=$5
user=$6
pass=$7

PATH=$DIR:$PATH

case $1 in
 start)

echo "
base {
 log_debug = off;
 log_info = off;
 log = stderr;
 daemon = on;
 redirector = iptables;
}
" >$DIR/redsocks.conf

 case $type in
  http)
 case $auth in
  true)
  echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = http-relay;
 login = \"$user\";
 password = \"$pass\";
} 
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8124;
 ip = $host;
 port = $port;
 type = http-connect;
 login = \"$user\";
 password = \"$pass\";
} 
" >>$DIR/redsocks.conf
   ;;
   false)
   echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = http-relay;
} 
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8124;
 ip = $host;
 port = $port;
 type = http-connect;
} 
 " >>$DIR/redsocks.conf
   ;;
 esac
   ;;
  socks5)
   case $auth in
  true)
    echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = socks5;
 login = \"$user\";
 password = \"$pass\";
 }
 " >>$DIR/redsocks.conf
   ;;
 false)
  echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = socks5;
 }
 " >>$DIR/redsocks.conf
   ;;
 esac
 ;;
   socks4)
   case $auth in
  true)
    echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = socks4;
 login = \"$user\";
 password = \"$pass\";
 }
 " >>$DIR/redsocks.conf
   ;;
 false)
  echo "
redsocks {
 local_ip = 127.0.0.1;
 local_port = 8123;
 ip = $host;
 port = $port;
 type = socks4;
 }
 " >>$DIR/redsocks.conf
   ;;
 esac
 ;;
 esac

 $DIR/redsocks -p $DIR/redsocks.pid -c $DIR/redsocks.conf
 ;;
stop)

  killall -9 redsocks
  killall -9 cntlm
  killall -9 stunnel
  killall -9 tproxy
  
  kill -9 `cat $DIR/redsocks.pid`
  
  rm $DIR/redsocks.pid
  
  rm $DIR/redsocks.conf
esac
