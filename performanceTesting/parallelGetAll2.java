import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class parallelGetAll2 {
    static List<CompletableFuture<Void>> futures = new ArrayList<>();

    public static void main(String[] args) {
        String filePath = "../database2.txt";
        if (args.length != 3) {
            System.out.println("Usage: java parallelPutAll {hostname} {port} {numRequest}");
            System.out.println("Example: java parallelPutAll dh2026pc11 54321 4000");
            return;
        }
        String address = args[0];
        String port = args[1];
        int numRequest = Integer.parseInt(args[2]);
        HttpClient httpClient = HttpClient.newHttpClient();
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
            lines.limit(numRequest).forEach(line -> {
                processLine(address, port, line, httpClient);
            });
            CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            combinedFuture.get();
        } catch (Exception e) {
        }
    }

    public static void processLine(String address, String port, String line, HttpClient httpClient) {
        String[] parts = line.split("\t");
        if (parts.length == 2) {
            String shortStr = parts[0];
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + address + ":" + port + "/" + shortStr))
                    .GET()
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            CompletableFuture<Void> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenAccept(body -> {
                        System.out.println("Response: " + body);
                    });
            futures.add(future);
        }
    }
}
