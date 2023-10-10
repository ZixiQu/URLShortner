#!/usr/bin/python3

import subprocess
import time
from multiprocessing import Pool
import random, string

counter = 0

def make_request(i):
    # request="dh2026pc16:54321/arnold"
    server_name = "dh2026pc16"
    shortResource = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(20))

    request = "http://" + server_name + ":54321/" + shortResource
    # print(i)
    start_time = time.time()
    response = subprocess.run(["curl", "-X", "GET", request], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if response.returncode != 0:
        print("cannot finish request")
    end_time = time.time()
    elapsed_time = end_time - start_time

    print("FUCK, THERE IS A REQUEST !!!")

    return elapsed_time

if __name__ == '__main__':
    total = 5000
    num_processes = 7  # You can adjust this to the desired number of parallel processes
    start = time.time()
    with Pool(num_processes) as pool:
        elapsed_times = pool.map(make_request, range(total))
    
    total_time = sum(elapsed_times)
    average_time = total_time / len(elapsed_times)
    print(f"Total Time: {total_time:.10f} seconds")
    print(f"Average Time: {average_time:.10f} seconds")
    print(f"Equals: {1/average_time:.10f} per seconds")
