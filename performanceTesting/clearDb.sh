#!/bin/bash

for host in `cat ../hosts`
do
    addr="${host%:*}"
    if timeout 3 ssh -n $addr true 2>/dev/null; then
        echo "Connection suceess $addr"
        ssh $addr "cd /virtual/group_0080; rm *.db" &
    else
        echo "Failed to connect to $addr"
    fi
done