#!/bin/bash
host=$1
DIR=$(pwd)
if timeout 3 ssh -n $host true 2>/dev/null;then
    echo "Connection suceess $host"
    ssh $host "cd $DIR; pkill java 2>/dev/null; sleep 3;
    nohup make run > ./log/\${HOSTNAME}.output 2>&1; echo $! > ./log/\${HOSTNAME}.pid" &
else
    echo "Failed to connect to $host"
fi
