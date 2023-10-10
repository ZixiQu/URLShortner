
for host in `cut -f1 -d$':' hosts`
do
	ssh $host "ps -ef | grep java | grep $USER"
done

