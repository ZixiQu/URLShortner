#!/bin/bash
DIR=$(pwd)
for host in `cut -f1 -d$':' hosts`
do
	if timeout 3 ssh -n $host true 2>/dev/null;then
		echo "Connection suceess $host"
		ssh $host "cd $DIR; 
		mkdir -p /virtual/group_0080/;
		chmod 777 /virtual/group_0080/;
		touch /virtual/group_0080/\${HOSTNAME}.db;
		chmod 777 /virtual/group_0080/\${HOSTNAME}.db;
		nohup make run > ./log/\${HOSTNAME}.output 2>&1; echo $! > ./log/\${HOSTNAME}.pid" &
	else
		echo "Failed to connect to $host"
	fi
done
