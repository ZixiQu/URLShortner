import random
import socket
import sys
import threading
import time
import string
import subprocess
import select
import shutil
import os
import sqlite3
from datetime import datetime

# the time interval that checks the status of each host. Wake up if process died.
CHECK_STATUS_INTERVAL = 15
DATA_REPLICATION_INTERVAL = 15
CLOSED = False
verbose = False
first_start = True

HOST = socket.gethostname()
with open('MonitorInfo', 'r') as file:
    lines = file.readlines()
    try:
        # Get the second line and convert it to an integer
        PORT = int(lines[1].strip())
    except IndexError:
        print("Can't find PORT in Monitor file")
    except ValueError:
        # Handle case where conversion to integer fails
        print("PORT value incorrectly defined")


server_list = []
port_list = []
with open('hosts', 'r') as file:
    for line in file:
        server_list.append(line.strip().split(':')[0])
        port_list.append(int(line.split(':')[1].strip()))
old_server_list = server_list[:]


def check_db_exists(host_name):
    command = ['ssh', host_name,
               f'test -f /virtual/group_0080/{host_name}.db && echo exists || echo not_exists']
    result = subprocess.run(command, capture_output=True,
                            text=True,
                            check=True,
                            )
    return result.stdout.strip() == 'exists'


def copy_db_to(source_host, dest_host, source_directory='/virtual/group_0080/', destination_directory='/virtual/group_0080/', source_file=None):
    # backup all database files
    time.sleep(1)
    try:
        if not source_file:
            source_file = source_host
        ssh_command = f'ssh {source_host} "[ -f "{source_directory}{source_file}.db" ] && scp {source_directory}{source_file}.db {dest_host}:{destination_directory}"'
        result = subprocess.run(ssh_command, check=True,
                                shell=True, stdout=subprocess.PIPE)
    except Exception as e:
        pass


def copy_local_db_to(source_host, dest_host, source_directory='/virtual/group_0080/', destination_directory='/virtual/group_0080/'):
    # backup all database files
    ssh_command = f'scp {source_directory}{source_host}.db {dest_host}:{destination_directory}'
    result = subprocess.run(ssh_command, check=True,
                            shell=True, stdout=subprocess.PIPE)


def remove_server_db(source_host, source_directory='/virtual/group_0080/'):
    # backup all database files
    ssh_command = f'ssh {source_host} "rm -f {source_directory}*.db"'
    result = subprocess.run(ssh_command, check=True,
                            shell=True, stdout=subprocess.PIPE)

# reference: https://stackoverflow.com/questions/76581535/python-equivalent-for-java-hashcode-function


def hash_code(str):
    hashCode = 0
    for char in str:
        hashCode = (hashCode * 31 + ord(char)) & (2**32 - 1)  # unsigned
    if hashCode & 2**31:
        hashCode -= 2**32  # make it signed
    return hashCode


def recover_db():
    global first_start
    if first_start:
        first_start = False
        pass
    else:
        try:
            for i in range(len(server_list)):
                if not check_db_exists(server_list[i]):
                    # recover .db from other replicas
                    back_up_server = server_list[(i+1) % len(server_list)]
                    print(
                        server_list[i], "does not have its .db file, trying to recover from", back_up_server)
                    copy_db_to(back_up_server,
                               server_list[i], source_file=server_list[i])
        except Exception as e:
            pass


def redistribute_data():
    # redistribute all database content to current servers

    # get all db to local
    local_dir_old = '/virtual/group_0080/old/'
    local_dir_new = '/virtual/group_0080/new/'
    isExist = os.path.exists(local_dir_old)
    if not isExist:
        os.makedirs(local_dir_old)
    else:
        shutil.rmtree(local_dir_old)
        os.makedirs(local_dir_old)
    isExist = os.path.exists(local_dir_new)
    if not isExist:
        os.makedirs(local_dir_new)
    else:
        shutil.rmtree(local_dir_new)
        os.makedirs(local_dir_new)

    # copy to local
    for server in old_server_list:
        try:
            copy_db_to(server, HOST, destination_directory=local_dir_old)
        except Exception as e:
            pass

    # remove all old db on old servers
    for server in old_server_list:
        remove_server_db(server)

    new_db_conn = {}
    for server in server_list:
        new_db_conn[server] = [sqlite3.connect(
            os.path.join(local_dir_new, server + '.db'))]
        new_db_conn[server].append(new_db_conn[server][0].cursor())
        create_table_sql = """
        CREATE TABLE IF NOT EXISTS URLMAP (short TEXT PRIMARY KEY, long TEXT)
        """
        new_db_conn[server][1].execute(create_table_sql)
        new_db_conn[server][0].commit()

    # distribute all data to new .db files in local
    for db_file in os.listdir(local_dir_old):
        if db_file.endswith('.db'):
            conn = sqlite3.connect(os.path.join(local_dir_old, db_file))
            cursor = conn.cursor()
            cursor.execute(f"SELECT * FROM URLMAP")
            rows = cursor.fetchall()
            for row in rows:
                short = row[0]
                new_server = server_list[hash_code(short) % len(server_list)]
                print(row)
                print(new_server)
                new_db_conn[new_server][1].executemany(
                    f"INSERT INTO URLMAP VALUES (?,?)", [row])
                new_db_conn[new_server][0].commit()

    for conn in new_db_conn.values():
        conn[0].commit()
        conn[0].close()

    # distribute all new files to servers
    for server in server_list:
        copy_local_db_to(server, server, source_directory=local_dir_new)


if len(sys.argv) > 1:
    if sys.argv[1] == '-r':
        if len(sys.argv) > 2:
            old_server_list.append(sys.argv[2])
        print("Redistributing data...")
        redistribute_data()
        sys.exit()
    else:
        print("Invalid argument.")
        sys.exit()

server_performances = {}
monitor_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
monitor_socket.bind((HOST, PORT))
monitor_socket.listen(6)
server_sockets = [monitor_socket]
print(f"Monitor is up, listening on port " + str(PORT))


def listen_for_reports():
    while True:
        try:
            read_sockets, _, exception_sockets = select.select(
                server_sockets, [], server_sockets)

            for notified_socket in read_sockets:
                # If notified_socket is server_socket, it's an incoming connection
                if notified_socket == monitor_socket:
                    client_socket, client_address = monitor_socket.accept()
                    server_sockets.append(client_socket)
                    print(
                        f"Server {client_address} is up and connected to Monitor.")

                # If it's not server_socket, it's a message from a server
                else:
                    message = notified_socket.recv(1024).decode()
                    # If message is empty, client disconnected
                    if not message:
                        print(
                            f"Client {notified_socket.getpeername()} disconnected")
                        server_sockets.remove(notified_socket)
                        notified_socket.close()
                    else:
                        handle(message)
        except Exception as e:
            print("the monitor node is shutting down, please do not kill process")
            break


def handle(host_name):
    if host_name in server_performances:
        server_performances[host_name].append(time.time())
    else:
        server_performances[host_name] = [time.time()]


def update_UI(host_name='a'):
    # parse the data and update the UI
    # use progress bar with top of 1000/sec
    print("=====================================")
    if host_name == 'a':
        for server in server_performances.keys():
            if len(server_performances[server]) <= 1:
                print(f"{server} - Response per second: NA / {1000}")
            else:
                response_per_sec = 100 / \
                    (server_performances[server][-1] -
                     server_performances[server][-2])
                print(f"{server} - Response per second: {response_per_sec:.2f}")
    else:
        server = host_name
        if server in server_performances:
            if len(server_performances[server]) <= 1:
                print(f"{server} - Response per second: NA / {1000}")
            else:
                response_per_sec = 100 / \
                    (server_performances[server][-1] -
                     server_performances[server][-2])
                print(f"{server} - Response per second: {response_per_sec:.2f}")
        else:
            print("Illegal host name.")


def update_log():
    # parse the data and update the UI
    # use progress bar with top of 1000/sec
    with open(f'./log/performance.csv', 'a') as f:
        for server in server_performances.keys():
            if len(server_performances[server]) <= 1:
                pass
            else:
                response_per_sec = 100 / \
                    (server_performances[server][-1] -
                        server_performances[server][-2])
                f.write(f"{server},{response_per_sec:.2f}\n")


def check_read(host_name, verbose=True):
    request = "http://" + host_name + ":12345/arnold"
    result = subprocess.run(["curl", "-X", "GET", request],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode == 0:
        if verbose:
            print(f"\nRead from {host_name} succeed!")
        return True
    else:
        if verbose:
            print(f"\nRead from {host_name} failed!")
        return False


def check_write(host_name, verbose=True):
    longResource = "http://" + \
        ''.join(random.choice(string.ascii_uppercase + string.digits)
                for _ in range(100))
    shortResource = ''.join(random.choice(
        string.ascii_uppercase + string.digits) for _ in range(20))
    request = "http://" + host_name + ":12345/?short=" + \
        shortResource+"&long="+longResource
    result = subprocess.run(["curl", "-X", "PUT", request],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if verbose:
        if result.stdout:
            print(f"Write to {host_name} succeed!")
        else:
            print(f"Write to {host_name} failed!")


def check_and_wake():
    # check the host regularly and try to wake it if dead
    update_log()
    for host_name in server_list:
        if not check_read(host_name, verbose=False):
            with open('./log/syslog', 'a') as f:
                f.write(
                    f"{datetime.now()} [{host_name}] no response, trying to wake up.\n")
            print(f"[{host_name}] no response, trying to wake up.")
            try:
                subprocess.run(["./startone.sh", host_name],
                               check=True, text=True)
            except subprocess.CalledProcessError:
                sys.exit(f"Try to wake host [{host_name}] failed.")
            except FileNotFoundError:
                sys.exit("Starting bash script not found.")


def replicate_data(replicate_num):
    for hostname in server_list:
        for r in range(replicate_num):
            if verbose:
                print("copy_db_to", hostname)
            copy_db_to(hostname, server_list[(
                server_list.index(hostname)+r+1) % len(server_list)])


def scheduler(interval, function, *args):
    # Schedules a function to run at specified intervals.
    while True and not CLOSED:
        function(*args)
        time.sleep(interval)


recover_thread = threading.Thread(
    target=scheduler, args=(DATA_REPLICATION_INTERVAL, recover_db))
recover_thread.start()

time.sleep(1)

listen_thread = threading.Thread(
    target=listen_for_reports, args=(), daemon=True)
listen_thread.start()

num_replica = int(len(server_list) * 1/3) + 1
replicate_thread = threading.Thread(
    target=scheduler, args=(DATA_REPLICATION_INTERVAL, replicate_data, num_replica-1))
replicate_thread.start()


# use bash to start all servers
try:
    subprocess.run(["./startall.sh"], check=True, text=True)
except subprocess.CalledProcessError:
    sys.exit("Bash script for starting error.")
except FileNotFoundError:
    sys.exit("Starting bash script not found.")

time.sleep(1)
wake_thread = threading.Thread(target=scheduler, args=(
    CHECK_STATUS_INTERVAL, check_and_wake))
wake_thread.start()

print("'a' for all server status\n'q' to quit\n'hostname' to see respond speed\n'w hostname' to test write\n'r hostname' to test read")
while True:
    try:
        user_input = input("Enter command: ")
        if user_input == 'q':
            print("Monitor is shutting down.")
            break
        if user_input.startswith('w'):
            check_write(user_input[2:])
        elif user_input.startswith('r'):
            check_read(user_input[2:])
        else:
            update_UI(user_input)

    except KeyboardInterrupt:
        print("Monitor is shutting down, closing all servers.")
        break

CLOSED = True
# use bash to kill all servers
try:
    subprocess.run(["./killall.sh"], check=True, text=True)
except subprocess.CalledProcessError:
    sys.exit("Bash script for killing error.")
except FileNotFoundError:
    sys.exit("Killing bash script not found.")
monitor_socket.close()
sys.exit()
