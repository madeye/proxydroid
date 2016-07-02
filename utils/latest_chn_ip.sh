#!/bin/sh

IP_COUNT_PER_LINE=4

# Get ip list and shrink for better performance by remove subnets which have netmasks >= 20.
ip_list=`wget -qO- https://github.com/17mon/china_ip_list/raw/master/china_ip_list.txt | grep -v \/[2-3][0-9]$`

# Format list as Java code
ip_line=""
for ip in $ip_list;
do
    ip_line="$ip_line \"$ip\","

    # New line at IP_COUNT_PER_LINE
    if [ `echo $ip_line | wc -w` = $IP_COUNT_PER_LINE ]; then
        echo $ip_line
        ip_line=""
    fi
done

# Remove last comma of the last line
echo ${ip_line%,}
