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
proxy_port=8123

 case $type in
  http)
  proxy_port=8124
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
 local_ip = 0.0.0.0;
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
 local_ip = 0.0.0.0;
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
 local_ip = 0.0.0.0;
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
 local_ip = 0.0.0.0;
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
 local_ip = 0.0.0.0;
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
 local_ip = 0.0.0.0;
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
 iptables -A INPUT -i ap+ -p tcp --dport 8123 -j ACCEPT
 iptables -A INPUT -i ap+ -p tcp --dport 8124 -j ACCEPT
 iptables -A INPUT -i lo -p tcp --dport 8123 -j ACCEPT
 iptables -A INPUT -i lo -p tcp --dport 8124 -j ACCEPT
 iptables -A INPUT -p tcp --dport 8123 -j DROP
 iptables -A INPUT -p tcp --dport 8124 -j DROP
 iptables -t nat -A PREROUTING -i ap+ -p tcp -d 192.168.43.1/24 -j RETURN
 iptables -t nat -A PREROUTING -i ap+ -p tcp -j REDIRECT --to $proxy_port
 ;;
stop)

 iptables -t nat -D PREROUTING -i ap+ -p tcp -d 192.168.43.1/24 -j RETURN
 iptables -t nat -D PREROUTING -i ap+ -p tcp -j REDIRECT --to 8123
 iptables -t nat -D PREROUTING -i ap+ -p tcp -j REDIRECT --to 8124
 iptables -D INPUT -i ap+ -p tcp --dport 8123 -j ACCEPT
 iptables -D INPUT -i ap+ -p tcp --dport 8124 -j ACCEPT
 iptables -D INPUT -i lo -p tcp --dport 8123 -j ACCEPT
 iptables -D INPUT -i lo -p tcp --dport 8124 -j ACCEPT
 iptables -D INPUT -p tcp --dport 8123 -j DROP
 iptables -D INPUT -p tcp --dport 8124 -j DROP

  killall -9 redsocks
  killall -9 cntlm
  killall -9 stunnel
  killall -9 tproxy

  kill -9 `cat $DIR/redsocks.pid`

  rm $DIR/redsocks.pid

  rm $DIR/redsocks.conf
esac
