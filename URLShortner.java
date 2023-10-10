import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.sql.*;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class URLShortner {

	static final File WEB_ROOT = new File(".");
	static final String DEFAULT_FILE = "index.html";
	static final String FILE_NOT_FOUND = "404.html";
	static final String METHOD_NOT_SUPPORTED = "not_supported.html";
	static final String REDIRECT_RECORDED = "redirect_recorded.html";
	static final String REDIRECT = "redirect.html";
	static final String NOT_FOUND = "notfound.html";
	static final String DATABASE = "database.txt";
	static final String hosts_file = "hosts";
	static final int POOLSIZE = 8;
	// port to listen connection
	static final int PORT = 12345;

	// verbose mode
	static final boolean verbose = true;
	static final boolean debug = true;

	// host information data structures.
	static ArrayList<String> hosts = new ArrayList<String>();
	static HashMap<String, Connection> host_to_db = new HashMap<String, Connection>();

	public static void main(String[] args) {
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

		connectAll();
		if (debug) {
			System.out.println(Arrays.asList(host_to_db));
		}
		String selfHostname = args[0];
		System.out.println("URLShortner running on " + selfHostname + PORT + " " + new Date());

		String monitorHostname = "dh2026pc15";
		int monitorPORT = 54444;

		try (BufferedReader reader = new BufferedReader(new FileReader("MonitorInfo"))) {
			// Read the Monitor hostname
			monitorHostname = reader.readLine();
			// Read the second line to PORT
			String secondLine = reader.readLine();
			if (secondLine != null) {
				try {
					// Try to convert the second line to PORT
					monitorPORT = Integer.parseInt(secondLine.trim());
				} catch (NumberFormatException e) {
					System.err.println("Cannot convert PORT number to integer: " + e.getMessage());
				}
			} else {
				System.err.println("No PORT number in the file.");
			}
		} catch (IOException e) {
			System.err.println("Error reading the MonitorInfo file: " + e.getMessage());
		}

		try {
			ServerSocket serverConnect = new ServerSocket(PORT);
			System.out.println("Server started.\nListening for connections on port : " + PORT + " ...\n");

			int monitorCount = 0;
			// we listen until user halts server execution

			Socket monitorSocket = new Socket(monitorHostname, monitorPORT);
			String monitorMessage = selfHostname;
			OutputStream monitorOutputStream = monitorSocket.getOutputStream();

			while (true) {
				if (verbose) {
					System.out.println("Connecton opened. (" + new Date() + ")");
				}
				Socket client = serverConnect.accept();
				BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
				String input = in.readLine();
				String s = null; // short URL
				Pattern pput = Pattern.compile("^PUT\\s+/\\?short=(\\S+)&long=(\\S+)\\s+(\\S+)$");
				Matcher mput = pput.matcher(input);
				if (mput.matches()) {
					s = mput.group(1);
				} else {
					Pattern pget = Pattern.compile("^(\\S+)\\s+/(\\S+)\\s+(\\S+)$");
					Matcher mget = pget.matcher(input);
					if (mget.matches()) {
						s = mget.group(2);
					}
				}
				if (null == s) {
					if (verbose)
						System.out.println("cannot read short");
					continue;
				}
				Connection right_conn = host_to_db.get(hosts.get(chooseServer(s)).split(":", 0)[0]);
				if (debug) {
					System.out.println(hosts.get(chooseServer(s)));
					System.out.println(Arrays.asList(host_to_db));
				}

				if (null != right_conn) {
					Handler w = new Handler(client, right_conn, input);
					pool.execute(w);
				} else {
					return_404(client);
				}

				if (monitorCount % 100 == 0) {
					// Start the sending thread to send status to Monitor
					Thread sendToMonitorThread = new Thread(() -> {
						try {
							monitorOutputStream.write(monitorMessage.getBytes());
							if (verbose)
								System.out.println("Status sent to Monitor server.");
						} catch (IOException e) {
							e.printStackTrace();
						}
					});
					sendToMonitorThread.start();
				}
				if (debug)
					System.out.println(monitorCount);
				monitorCount++;
			}
		} catch (IOException e) {
			System.err.println("Server Connection error : " + e.toString());
			e.printStackTrace();
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

	public static void handle(Socket connect, Connection conn, String input) {
		BufferedReader in = null;
		PrintWriter out = null;
		BufferedOutputStream dataOut = null;

		try {
			in = new BufferedReader(new InputStreamReader(connect.getInputStream()));
			out = new PrintWriter(connect.getOutputStream());
			dataOut = new BufferedOutputStream(connect.getOutputStream());

			if (verbose)
				System.out.println("first line: " + input);
			Pattern pput = Pattern.compile("^PUT\\s+/\\?short=(\\S+)&long=(\\S+)\\s+(\\S+)$");
			Matcher mput = pput.matcher(input);
			if (mput.matches()) {
				String shortResource = mput.group(1);
				String longResource = mput.group(2);

				save(shortResource, longResource, conn);

				File file = new File(WEB_ROOT, REDIRECT_RECORDED);
				int fileLength = (int) file.length();
				String contentMimeType = "text/html";
				// read content to return to client
				byte[] fileData = readFileData(file, fileLength);

				out.println("HTTP/1.1 200 OK");
				out.println("Server: Java HTTP Server/Shortner : 1.0");
				out.println("Date: " + new Date());
				out.println("Content-type: " + contentMimeType);
				out.println("Content-length: " + fileLength);
				out.println();
				out.flush();

				dataOut.write(fileData, 0, fileLength);
				dataOut.flush();
			} else {
				Pattern pget = Pattern.compile("^(\\S+)\\s+/(\\S+)\\s+(\\S+)$");
				Matcher mget = pget.matcher(input);
				if (mget.matches()) {
					String shortResource = mget.group(2);

					String longResource = find(shortResource, conn);
					if (longResource != null) {
						File file = new File(WEB_ROOT, REDIRECT);
						int fileLength = (int) file.length();
						String contentMimeType = "text/html";

						// read content to return to client
						byte[] fileData = readFileData(file, fileLength);

						out.println("HTTP/1.1 307 Temporary Redirect");
						out.println("Location: " + longResource);
						out.println("Server: Java HTTP Server/Shortner : 1.0");
						out.println("Date: " + new Date());
						out.println("Content-type: " + contentMimeType);
						out.println("Content-length: " + fileLength);
						out.println();
						out.flush();

						dataOut.write(fileData, 0, fileLength);
						dataOut.flush();
					} else {
						File file = new File(WEB_ROOT, FILE_NOT_FOUND);
						int fileLength = (int) file.length();
						String content = "text/html";
						byte[] fileData = readFileData(file, fileLength);

						out.println("HTTP/1.1 404 File Not Found");
						out.println("Server: Java HTTP Server/Shortner : 1.0");
						out.println("Date: " + new Date());
						out.println("Content-type: " + content);
						out.println("Content-length: " + fileLength);
						out.println();
						out.flush();

						dataOut.write(fileData, 0, fileLength);
						dataOut.flush();
					}
				}
			}
		} catch (Exception e) {
			System.err.println("Server error");
			e.printStackTrace();
		} finally {
			try {
				in.close();
				out.close();
				connect.close(); // we close socket connection
			} catch (Exception e) {
				System.err.println("Error closing stream : " + e.getMessage());
			}

			if (verbose) {
				System.out.println("Connection closed.\n");
			}
		}
	}

	public static Connection connectDB(String dbname) {
		String db_url = "jdbc:sqlite:/virtual/group_0080/" + dbname + ".db";

		try {
			Connection connection = DriverManager.getConnection(db_url);
			if (connection != null) {
				System.out.println("Connected to the database: " + dbname);

				Statement statement = connection.createStatement();
				String createTableSQL = "CREATE TABLE IF NOT EXISTS URLMAP (short TEXT PRIMARY KEY, long TEXT)";
				statement.execute(createTableSQL);
				System.out.println("Table URLMAP has been created.");
				return connection;
			}
		} catch (SQLException e) {
			System.err.println("Error: " + e.getMessage());
		}

		return null;
	}

	public static void return_404(Socket client) {
		try {
			PrintWriter out = new PrintWriter(client.getOutputStream());
			BufferedOutputStream dataOut = new BufferedOutputStream(client.getOutputStream());

			File file = new File(WEB_ROOT, FILE_NOT_FOUND);
			int fileLength = (int) file.length();
			String content = "text/html";
			byte[] fileData = readFileData(file, fileLength);

			out.println("HTTP/1.1 404 File Not Found");
			out.println("Server: Java HTTP Server/Shortner : 1.0");
			out.println("Date: " + new Date());
			out.println("Content-type: " + content);
			out.println("Content-length: " + fileLength);
			out.println();
			out.flush();

			dataOut.write(fileData, 0, fileLength);
			dataOut.flush();
		} catch (IOException e) {
			System.out.println("return_404()");
			e.printStackTrace();
		}

	}

	/**
	 * connect to all databases, store hostname-Connection to host_to_db.
	 */
	public static void connectAll() {
		String fileLocation = "/virtual/group_0080";
		Path dirPath = Paths.get(fileLocation);

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
			for (Path entry : stream) {
				if (Files.isRegularFile(entry)) {
					String filename = entry.getFileName().toString();
					filename = filename.split("\\.", 0)[0];
					if (debug)
						System.out.println("[" + filename + "]");
					host_to_db.put(filename, connectDB(filename));
				}
			}
		} catch (IOException e) {
			System.out.println("connectAll(): cannot open folder to read: " + e.getMessage());
		}

	}

	private static String find(String shortURL, Connection conn) {
		String longURL = null;
		try {
			Statement statement = conn.createStatement();
			String selectSQL = "SELECT * FROM URLMAP WHERE short = '" + shortURL + "'";
			ResultSet resultSet = statement.executeQuery(selectSQL);

			while (resultSet.next()) {
				longURL = resultSet.getString("long");
			}
			resultSet.close();
			statement.close();

		} catch (SQLException e) {

		}
		return longURL;
	}

	private static void save(String shortURL, String longURL, Connection conn) {
		try {
			Statement statement = conn.createStatement();
			String insertSQL = "INSERT INTO URLMAP (short, long) VALUES ('" + shortURL + "','" + longURL + "')";
			int rowsInserted = statement.executeUpdate(insertSQL);
			if (rowsInserted > 0) {
				System.out.println("URL inserted: " + shortURL + ", " + longURL);
			} else {
				System.out.println("Insert failed.");
			}
			statement.close();
		} catch (SQLException e) {
			System.err.println("Error: " + e.getMessage());
		}
		return;
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

class Handler implements Runnable {
	Socket connect;
	Connection conn;
	String input;

	public Handler(Socket connect, Connection conn, String input) {
		this.connect = connect;
		this.conn = conn;
		this.input = input;
	}

	public void run() {
		URLShortner.handle(this.connect, this.conn, this.input);
	}
}
