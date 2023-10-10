import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.Math;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyServer {
    static final File WEB_ROOT = new File(".");
    static final String DEFAULT_FILE = "index.html";
    static final String FILE_NOT_FOUND = "404.html";
    static final String METHOD_NOT_SUPPORTED = "not_supported.html";
    static final String REDIRECT_RECORDED = "redirect_recorded.html";
    static final String REDIRECT = "redirect.html";
    static final String NOT_FOUND = "notfound.html";
    static final String DATABASE = "database.txt";
    static final int localport = 54321;
    static final boolean verbose = false;
    static final boolean debug = false;
    static final boolean USECACHE = true;
    static final int POOLSIZE = 12;
    static final String hosts_file = "hosts";

    static ArrayList<String> hosts = new ArrayList<String>();

    public static void main(String[] args) throws IOException {

        try (BufferedReader br = new BufferedReader(new FileReader(hosts_file))) {
            String line;
            while ((line = br.readLine()) != null) {
                hosts.add(line);
            }
        } catch (IOException e) {
            System.err.println("Error reading the file: " + e.getMessage());
        }

        if (debug) {
            System.out.println(numReplicas());
            for (String s : hosts) {
                System.out.println(s);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(POOLSIZE);
        Map<String, String> threadSafeCache = Collections.synchronizedMap(new Cache(5000));

        System.out.println("Proxy has started. Listening on " + localport);
        ServerSocket ss = new ServerSocket(localport);
        while (true) {
            try {
                Socket client = ss.accept();
                System.out.println("one new connection from" + client.getRemoteSocketAddress().toString());
                // start a thread to handle service.
                ServerWorker sw = new ServerWorker(client, threadSafeCache); // thread pm goes in here.
                pool.execute(sw);
            } catch (IOException e) {
                System.err.println("Proxy Connection error : " + e.getMessage());
            } finally {
            }
        }
    }

    /**
     * Given short s, it will decide which server the proxy should forward to
     */
    public static int chooseServer(String s) {
        return Math.floorMod(s.hashCode(), hosts.size());
    }

    /**
     * number of data replications depands on number of hosts
     * numReplicas = int(n * 1/3) + 1
     */
    public static int numReplicas() {
        return (int) hosts.size() / 3 + 1;
    }

    /**
     * proxy single client request. it will return.
     */
    public static void runServer(Socket client, Map<String, String> cache)
            throws IOException {
        // Create a ServerSocket to listen for connections with
        PrintWriter clientPrintWriter = null;
        BufferedOutputStream clientDataStream = null;

        try {
            // Wait for a connection on the local port

            final InputStream streamFromClient = client.getInputStream();
            final OutputStream streamToClient = client.getOutputStream();

            BufferedReader in = new BufferedReader(new InputStreamReader(streamFromClient));
            ;
            String input = in.readLine();

            if (verbose) {
                System.out.println("first line: " + input);
                System.out.println("client Input: " + input);
            }

            String s = null; // short
            String long_s = null;
            Pattern pput = Pattern.compile("^PUT\\s+/\\?short=(\\S+)&long=(\\S+)\\s+(\\S+)$");
            Matcher mput = pput.matcher(input);
            if (mput.matches()) {
                s = mput.group(1);
                long_s = mput.group(2);
            } else {
                Pattern pget = Pattern.compile("^(\\S+)\\s+/(\\S+)\\s+(\\S+)$");
                Matcher mget = pget.matcher(input);
                if (mget.matches()) {
                    s = mget.group(2);
                }
            }
            if (s != null) {
                // Alex: try find short <s> from cache. If found, serve the client
                if (long_s != null) {
                    // it is a put request
                    if (verbose)
                        System.out.println("PUT Storing to cache, short = " + s + " long = " + long_s);
                    if (USECACHE)
                        cache.put(s, long_s);
                }
                // else it is a GET request
                String cachedResponse = null;
                if (USECACHE) {
                    cachedResponse = cache.get(s);
                }
                // Make a connection to the real server.
                // If we cannot make to the primary server, try backup server
                // If we cannot connect to the server, send an error to the
                // client, disconnect, and continue waiting for connections.
                if (cachedResponse != null && long_s == null) {
                    // we have a cached response, write to server directly
                    clientPrintWriter = new PrintWriter(streamToClient);
                    clientDataStream = new BufferedOutputStream(streamToClient);
                    File file = new File(WEB_ROOT, REDIRECT);
                    int fileLength = (int) file.length();
                    String contentMimeType = "text/html";

                    // read content to return to client
                    byte[] fileData = readFileData(file, fileLength);

                    clientPrintWriter.println("HTTP/1.1 307 Temporary Redirect");
                    clientPrintWriter.println("Location: " + cachedResponse);
                    clientPrintWriter.println("Server: Java HTTP Server/Shortner : 1.0");
                    clientPrintWriter.println("Date: " + new Date());
                    clientPrintWriter.println("Content-type: " + contentMimeType);
                    clientPrintWriter.println("Content-length: " + fileLength);
                    clientPrintWriter.println();
                    clientPrintWriter.flush();

                    clientDataStream.write(fileData, 0, fileLength);
                    clientDataStream.flush();
                    if (verbose)
                        System.out.println("Cached result found, result =" + cachedResponse);
                } else {
                    // no cached response, send to server to do
                    if (verbose)
                        System.out.println("Resource not found in cache");
                    Socket server = null;

                    for (int i = 0; i < numReplicas(); i++) {
                        try {
                            int serverNum = chooseServer(s) + i;
                            serverNum = Math.floorMod(serverNum, hosts.size());
                            if (debug)
                                System.out.println("serverNum: " + serverNum);
                            String host = hosts.get(serverNum);
                            server = new Socket(host.split(":", 0)[0], Integer.parseInt(host.split(":", 0)[1]));
                            break;
                        } catch (IOException e) {
                            System.out.println("Proxy server cannot connect to host " + i);
                        } catch (Exception e) {
                            System.out.println("more exception:");
                            e.printStackTrace();
                        }
                    }

                    // Make a connection to the real server.
                    // If we cannot connect to the server, send an error to the
                    // client, disconnect, and continue waiting for connections.
                    // Get server streams.

                    if (null == server) {
                        if (verbose)
                            System.out.println("All server is not reachable");
                        URLShortner.return_404(client);
                        return;
                    }

                    final InputStream streamFromServer = server.getInputStream();
                    final OutputStream streamToServer = server.getOutputStream();

                    byte[] input_b = input.getBytes(StandardCharsets.UTF_8);
                    try {
                        streamToServer.write(input_b);
                        streamToServer.flush();
                        server.shutdownOutput();
                    } catch (IOException e) {
                        System.out.println("cannot write to streamToServer: \n");
                        e.printStackTrace();
                    }
                    // Read the server's responses
                    // and pass them back to the client.
                    int bytesRead;
                    try {
                        byte[] reply = new byte[4096];
                        String str_reply = "";
                        while ((bytesRead = streamFromServer.read(reply)) != -1) {
                            // Alex: cache the response.
                            String strBytesRead = new String(reply);
                            str_reply = str_reply + strBytesRead;
                            streamToClient.write(reply, 0, bytesRead);
                            streamToClient.flush();
                        }
                        // it is a get request, get redirect location, then
                        // write new location to cache Location:s*(.+?)(?:\r\n|\n)
                        Pattern pattern = Pattern.compile("Location:s*(.+?)(?:\\r\\n|\\n)");
                        Matcher matcher = pattern.matcher(str_reply);
                        if (matcher.find()) {
                            String location = matcher.group(1);
                            if (USECACHE)
                                cache.put(s, location);
                        } else {
                            if (debug)
                                System.out.println("Location header not found.");
                            if (debug)
                                System.out.println(str_reply);
                        }

                        if (server != null) {
                            server.close();
                        }
                    } catch (IOException e) {
                    }
                }
                // The server closed its connection to us, so we close our
                // connection to our client.
                try {
                    if (client != null)
                        client.shutdownOutput();
                } catch (IOException e) {
                    System.out.println("client.shutdownOutput(): " + e.getMessage());
                }

            } // if (s != null)
        } catch (IOException e) {
            System.out.println("ERROR01");
        }
    }

    private static byte[] readFileData(File file, int fileLength) throws IOException {
        FileInputStream fileIn = null;
        byte[] fileData = new byte[fileLength];

        try {
            fileIn = new FileInputStream(file);
            fileIn.read(fileData);
        } finally {
            if (fileIn != null)
                fileIn.close();
        }

        return fileData;
    }
}

class ServerWorker implements Runnable {
    Socket client;
    private Map<String, String> cache;

    public ServerWorker(Socket client, Map<String, String> cache) {
        this.client = client;
        this.cache = cache;
    }

    public void run() {
        try {
            ProxyServer.runServer(this.client, this.cache);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class Cache extends LinkedHashMap<String, String> {
    private final int capacity;

    public Cache(int capacity) {
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
        return size() > capacity; // Remove the eldest entry if the size exceeds the capacity
    }
}
