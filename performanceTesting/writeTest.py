#!/usr/bin/python3

import random, string, subprocess

import sys

server_name = sys.argv[1]

for i in range(10000):
	longResource = "http://"+''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(100))
	shortResource = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(20))

	request="http://" + server_name + ":12345/?short="+shortResource+"&long="+longResource
	# print(request)
	result = subprocess.run(["curl", "-X", "PUT", request], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
	print("STDOUT:", result.stdout)
