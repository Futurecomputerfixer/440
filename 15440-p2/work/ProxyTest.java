import java.io.*;
import java.nio.file.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class ProxyTest {
    public static void main(String[] args) {
        try {
            // ---------------------------
            // SETUP SERVER ENVIRONMENT
            // ---------------------------
            File serverRoot = new File("serverRoot");
            if (!serverRoot.exists()) {
                serverRoot.mkdirs();
            }
            // Create a test file in the server directory
            File testFile = new File(serverRoot, "test.txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(testFile))) {
                pw.print("Hello, world!");
            }
            // Start the server (RMI)
            int serverPort = 1099;
            Server server = new Server(serverRoot);
            Registry registry = LocateRegistry.createRegistry(serverPort);
            registry.rebind("FileServer", server);
            System.out.println("Server started on port " + serverPort);
            
            // ---------------------------
            // SETUP PROXY ENVIRONMENT
            // ---------------------------
            File cacheDir = new File("cacheDir");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            // Set cache size to 1MB (adjust as needed)
            long cacheSize = 1_000_000;
            Proxy.RemoteFileServer remoteServer = (Proxy.RemoteFileServer) registry.lookup("FileServer");
            Proxy.CacheManager cacheManager = new Proxy.CacheManager(cacheDir, cacheSize);
            Proxy.FileHandlingFactory factory = new Proxy.FileHandlingFactory(cacheManager, remoteServer);
            // Create a FileHandling client instance from the factory
            Proxy.FileHandling client = factory.newclient();
            
            // ---------------------------
            // TEST 1: Read-Only Caching Test
            // ---------------------------
            System.out.println("\nTEST 1: Read-Only Caching Test");
            int fd = client.open("test.txt", OpenOption.READ);
            if (fd < 0) {
                System.out.println("Failed to open test.txt for reading. Error: " + fd);
            } else {
                byte[] buf = new byte[1024];
                long bytesRead = client.read(fd, buf);
                System.out.println("Content read: " + new String(buf, 0, (int)bytesRead));
                client.close(fd);
            }
            
            // ---------------------------
            // TEST 2: Concurrent Reads
            // ---------------------------
            System.out.println("\nTEST 2: Concurrent Reads");
            Thread t1 = new Thread(() -> {
                try {
                    Proxy.FileHandling c1 = factory.newclient();
                    int fd1 = c1.open("test.txt", OpenOption.READ);
                    byte[] b = new byte[1024];
                    long n = c1.read(fd1, b);
                    System.out.println("Thread 1 read: " + new String(b, 0, (int)n));
                    c1.close(fd1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            Thread t2 = new Thread(() -> {
                try {
                    Proxy.FileHandling c2 = factory.newclient();
                    int fd2 = c2.open("test.txt", OpenOption.READ);
                    byte[] b = new byte[1024];
                    long n = c2.read(fd2, b);
                    System.out.println("Thread 2 read: " + new String(b, 0, (int)n));
                    c2.close(fd2);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            t1.start(); t2.start();
            t1.join(); t2.join();
            
            // ---------------------------
            // TEST 3: Write-Then-Read Consistency
            // ---------------------------
            System.out.println("\nTEST 3: Write-Then-Read Consistency");
            int fdWrite = client.open("test.txt", OpenOption.WRITE);
            if (fdWrite < 0) {
                System.out.println("Failed to open test.txt for writing. Error: " + fdWrite);
            } else {
                byte[] newData = "Version2".getBytes();
                client.write(fdWrite, newData);
                client.close(fdWrite);
            }
            int fdRead = client.open("test.txt", OpenOption.READ);
            byte[] buf2 = new byte[1024];
            long n2 = client.read(fdRead, buf2);
            System.out.println("New content: " + new String(buf2, 0, (int)n2));
            client.close(fdRead);
            
            // ---------------------------
            // TEST 4: CREATE_NEW File Test
            // ---------------------------
            System.out.println("\nTEST 4: CREATE_NEW File Test");
            int fdNew = client.open("newfile.txt", OpenOption.CREATE_NEW);
            if (fdNew < 0) {
                System.out.println("Failed to create newfile.txt. Error: " + fdNew);
            } else {
                client.close(fdNew);
            }
            int fdNew2 = client.open("newfile.txt", OpenOption.CREATE_NEW);
            if (fdNew2 < 0) {
                System.out.println("Correctly failed to create newfile.txt again. Error: " + fdNew2);
            } else {
                System.out.println("Error: Created newfile.txt twice!");
                client.close(fdNew2);
            }
            
            // ---------------------------
            // TEST 5: Unlink Test
            // ---------------------------
            System.out.println("\nTEST 5: Unlink Test");
            int fdU = client.open("unlinkfile.txt", OpenOption.CREATE);
            if (fdU >= 0) {
                client.write(fdU, "To be deleted".getBytes());
                client.close(fdU);
            }
            int unlinkResult = client.unlink("unlinkfile.txt");
            System.out.println("Unlink result: " + unlinkResult);
            int fdAfterUnlink = client.open("unlinkfile.txt", OpenOption.READ);
            if (fdAfterUnlink < 0) {
                System.out.println("Correctly failed to open unlinked file.");
            } else {
                System.out.println("Error: unlinked file still accessible!");
                client.close(fdAfterUnlink);
            }
            
            // ---------------------------
            // TEST 6: LRU Eviction Test
            // ---------------------------
            System.out.println("\nTEST 6: LRU Eviction Test");
            // Create several large files (e.g., 200KB each) to exceed cache size.
            for (int i = 0; i < 10; i++) {
                String fname = "largefile" + i + ".txt";
                int fdL = client.open(fname, OpenOption.CREATE_NEW);
                if (fdL >= 0) {
                    byte[] data = new byte[200 * 1024];
                    Arrays.fill(data, (byte)('A' + i));
                    client.write(fdL, data);
                    client.close(fdL);
                }
            }
            // Access some of the large files repeatedly
            for (int i = 0; i < 5; i++) {
                int fdLR = client.open("largefile" + i + ".txt", OpenOption.READ);
                byte[] b = new byte[1024];
                client.read(fdLR, b);
                client.close(fdLR);
            }
            System.out.println("LRU Eviction Test complete. Check log messages for eviction info.");

            // ---------------------------
            // TEST 7: Huge Read (Chunking) Test
            // ---------------------------
            System.out.println("\nTEST 7: Huge Read (Chunking) Test");
            int fdHuge = client.open("hugefile.txt", OpenOption.CREATE_NEW);
            if (fdHuge >= 0) {
                byte[] hugeData = new byte[300 * 1024]; // 300KB file
                for (int i = 0; i < hugeData.length; i++) {
                    hugeData[i] = (byte)('0' + (i % 10));
                }
                client.write(fdHuge, hugeData);
                client.close(fdHuge);
            }
            int fdHugeRead = client.open("hugefile.txt", OpenOption.READ);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            long r;
            while ((r = client.read(fdHugeRead, chunk)) > 0) {
                baos.write(chunk, 0, (int)r);
            }
            client.close(fdHugeRead);
            System.out.println("Huge file read, total bytes: " + baos.size());
            
            // Cleanup
            client.clientdone();
            System.out.println("\nAll tests completed.");
            
            // Allow time for asynchronous logs to flush, then exit.
            TimeUnit.SECONDS.sleep(1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
