#!/bin/bash
for host in `cut -f1 -d$':' hosts`
do
	ssh $host "pkill java > /dev/null 2>&1" &
done
