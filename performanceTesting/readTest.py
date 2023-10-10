#!/usr/bin/python3

import random, string, subprocess
import time
import sys

server_name = sys.argv[1]

for i in range(300000000):
	# request="http://localhost:808/000000000000000000000000000000000000000000000"
	request="http://" + server_name + ":8085/arnold"
	start_time = time.time()
	print(i)
	result = subprocess.run(["curl", "-X", "GET", request], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
	print("STDOUT:", result.stdout)
	print(f"--- {time.time() - start_time} seconds ---")