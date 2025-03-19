import java.io.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.rmi.Remote;

public class Proxy {
    // --- RemoteFileServer interface used for RMI ---
    public interface RemoteFileServer extends Remote {
        // Get file metadata (size and timestamp)
        FileMetadata getFileMetadata(String path) throws RemoteException;

        // Fetch a chunk of file data starting at offset with specified size
        byte[] fetchFileChunk(String path, long offset, int chunkSize) throws RemoteException;

        // Push a chunk of file data starting at offset
        int pushFileChunk(String path, byte[] data, long offset) throws RemoteException;

        // Delete a file on the server
        int deleteFile(String path) throws RemoteException;
    }

    // File metadata class for version checking
    public static class FileMetadata implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long size;
        public final long lastModified;
        public final boolean exists;

        public FileMetadata(long size, long lastModified, boolean exists) {
            this.size = size;
            this.lastModified = lastModified;
            this.exists = exists;
        }
    }

    // --- Cache Manager Class ---
    private static class CacheManager {
        private final File cacheDir;
        private final long maxCacheSize;
        private final Map<String, CacheEntry> cacheEntries = new ConcurrentHashMap<>();
        private final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();
        private long currentCacheSize = 0;
        private final LinkedHashMap<String, Long> lruMap = new LinkedHashMap<>(100, 0.75f, true);
        public static final int CHUNK_SIZE = 50000; // 50KB chunks for file transfer
        private static final boolean DEBUG = true;
        
        // Cache for file metadata to reduce server queries
        private final Map<String, FileMetadata> metadataCache = new ConcurrentHashMap<>();
        
        // Track files that are currently in use to prevent eviction
        private final Map<String, Integer> inUseFiles = new ConcurrentHashMap<>();

        public CacheManager(File cacheDir, long maxCacheSize) {
            this.cacheDir = cacheDir;
            this.maxCacheSize = maxCacheSize;
            calculateInitialCacheSize();
        }
        
        // Get cached metadata for a file
        public FileMetadata getCachedMetadata(String path) {
            return metadataCache.get(path);
        }
        
        // Update or clear the metadata cache for a file
        public void updateMetadataCache(String path, FileMetadata metadata) {
            if (metadata == null) {
                metadataCache.remove(path);
                if (DEBUG) {
                    System.out.println("Removed metadata from cache for: " + path);
                }
            } else {
                metadataCache.put(path, metadata);
                if (DEBUG) {
                    System.out.println("Updated metadata cache for: " + path);
                }
            }
        }
        
        // Mark a file as in-use
        public void markFileInUse(String path) {
            inUseFiles.compute(path, (k, v) -> (v == null) ? 1 : v + 1);
            if (DEBUG) {
                System.out.println("Marked file in use: " + path + " count: " + inUseFiles.get(path));
            }
        }
        
        // Mark a file as no longer in use
        public void markFileNotInUse(String path) {
            inUseFiles.computeIfPresent(path, (k, v) -> {
                int result = v - 1;
                if (DEBUG) {
                    System.out.println("Unmarking file in use: " + path + " count now: " + result);
                }
                return (result <= 0) ? null : result;
            });
        }
        
        // Check if a file is currently in use
        public boolean isFileInUse(String path) {
            return inUseFiles.containsKey(path);
        }

        private void calculateInitialCacheSize() {
            long totalSize = 0;
            if (cacheDir.exists() && cacheDir.isDirectory()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            totalSize += file.length();
                            String relativePath = getRelativePath(file);
                            cacheEntries.put(relativePath, new CacheEntry(file, file.lastModified()));
                            lruMap.put(relativePath, System.currentTimeMillis());
                        }
                    }
                }
            }
            currentCacheSize = totalSize;
            if (DEBUG) {
                System.out
                        .println("Initial cache size: " + currentCacheSize + " bytes, Max: " + maxCacheSize + " bytes");
            }
        }

        public ReentrantReadWriteLock getFileLock(String path) {
            return fileLocks.computeIfAbsent(path, k -> new ReentrantReadWriteLock());
        }

        public boolean isFileCachedAndUpToDate(String path, FileMetadata serverMetadata) {
            CacheEntry entry = cacheEntries.get(path);
            if (entry == null)
                return false;

            File cachedFile = new File(cacheDir, path);
            if (!cachedFile.exists()) {
                cacheEntries.remove(path);
                return false;
            }

            return entry.timestamp >= serverMetadata.lastModified && cachedFile.length() == serverMetadata.size;
        }

        public File getCachedFile(String path) {
            updateFileUsage(path);
            return new File(cacheDir, path);
        }

        public void updateFileUsage(String path) {
            lruMap.put(path, System.currentTimeMillis());
            if (DEBUG) {
                System.out.println("Updated LRU for: " + path);
            }
        }

        public void updateCacheEntry(String path, long timestamp, long fileSize) {
            File cachedFile = new File(cacheDir, path);
            CacheEntry oldEntry = cacheEntries.get(path);

            if (oldEntry != null) {
                currentCacheSize -= oldEntry.file.length();
            }
            currentCacheSize += fileSize;

            cacheEntries.put(path, new CacheEntry(cachedFile, timestamp));
            updateFileUsage(path);

            if (DEBUG) {
                System.out.println("Updated cache entry for: " + path + ", current cache size: " + currentCacheSize);
            }

            evictIfNeeded();
        }

        private void evictIfNeeded() {
            if (currentCacheSize <= maxCacheSize)
                return;

            if (DEBUG) {
                System.out
                        .println("Cache eviction needed. Current size: " + currentCacheSize + ", Max: " + maxCacheSize);
            }

            List<Map.Entry<String, Long>> entries = new ArrayList<>(lruMap.entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getValue));

            for (Map.Entry<String, Long> entry : entries) {
                String path = entry.getKey();
                
                // Skip files that are in use
                if (isFileInUse(path)) {
                    if (DEBUG) {
                        System.out.println("Skipping " + path + " during eviction - file is in use");
                    }
                    continue;
                }
                
                ReentrantReadWriteLock lock = getFileLock(path);

                if (lock.writeLock().tryLock()) {
                    try {
                        CacheEntry cacheEntry = cacheEntries.get(path);
                        if (cacheEntry != null) {
                            File file = cacheEntry.file;
                            long fileSize = file.length();

                            if (file.delete()) {
                                currentCacheSize -= fileSize;
                                cacheEntries.remove(path);
                                lruMap.remove(path);
                                // Also clear metadata cache
                                updateMetadataCache(path, null);

                                if (DEBUG) {
                                    System.out.println("Evicted: " + path + " (" + fileSize + " bytes)");
                                }

                                if (currentCacheSize <= maxCacheSize) {
                                    break;
                                }
                            }
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            }
        }

        public void removeFromCache(String path) {
            CacheEntry entry = cacheEntries.remove(path);
            if (entry != null) {
                currentCacheSize -= entry.file.length();
                lruMap.remove(path);
                // Also clear metadata cache
                updateMetadataCache(path, null);
                if (DEBUG) {
                    System.out.println("Removed from cache: " + path);
                }
            }
        }

        private String getRelativePath(File file) {
            Path cachePath = cacheDir.toPath().normalize();
            Path filePath = file.toPath().normalize();
            return cachePath.relativize(filePath).toString().replace('\\', '/');
        }

        private static class CacheEntry {
            final File file;
            final long timestamp;

            CacheEntry(File file, long timestamp) {
                this.file = file;
                this.timestamp = timestamp;
            }
        }
    }

    // --- FileHandling implementation ---
    private static class FileHandler implements FileHandling {

        private static class FileEntry {
            RandomAccessFile raf;
            String mode;
            boolean dirty;
            String relPath;
            long timestamp;
            int refCount;
            String sessionPath; // Path to session-specific copy

            FileEntry(RandomAccessFile raf, String mode, String relPath, long timestamp) {
                this.raf = raf;
                this.mode = mode;
                this.dirty = false;
                this.relPath = relPath;
                this.timestamp = timestamp;
                this.refCount = 1;
                this.sessionPath = relPath;
            }

            void incrementRefCount() {
                refCount++;
            }

            boolean decrementRefCount() {
                return --refCount <= 0;
            }
        }

        private final Map<Integer, FileEntry> openFiles = new HashMap<>();
        private int nextFd = 3; // 0,1,2 reserved.
        private final CacheManager cacheManager;
        private final RemoteFileServer remoteServer;
        private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("debug", "false"));
        private final String clientId; // Unique ID for this client

        public FileHandler(CacheManager cacheManager, RemoteFileServer remoteServer) {
            this.cacheManager = cacheManager;
            this.remoteServer = remoteServer;
            // Generate a unique client ID
            this.clientId = UUID.randomUUID().toString();
            if (DEBUG) {
                System.out.println("FileHandler initialized with clientId: " + clientId);
            }
        }

        private File resolvePath(String path) throws IOException {
            File cacheFile = new File(cacheManager.cacheDir, path).getCanonicalFile();
            if (DEBUG) {
                System.out.println("Resolving path: " + path + " -> " + cacheFile);
            }
            if (!cacheFile.getPath().startsWith(cacheManager.cacheDir.getCanonicalPath())) {
                if (DEBUG) {
                    System.out.println("Resolved file " + cacheFile + " is outside of cache dir");
                }
                return null;
            }
            return cacheFile;
        }

        private boolean fetchFromServer(String path, File targetFile) throws RemoteException, IOException {
            // First check cached metadata
            FileMetadata metadata = cacheManager.getCachedMetadata(path);
            
            // If not in cache, fetch from server
            if (metadata == null) {
                metadata = remoteServer.getFileMetadata(path);
                // Update metadata cache
                cacheManager.updateMetadataCache(path, metadata);
            }
            
            if (metadata == null || !metadata.exists) {
                return false;
            }

            targetFile.getParentFile().mkdirs();

            File tempFile = new File(targetFile.getParentFile(), "." + targetFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                long offset = 0;
                long fileSize = metadata.size;

                while (offset < fileSize) {
                    int size = (int) Math.min(CacheManager.CHUNK_SIZE, fileSize - offset);
                    byte[] chunk = remoteServer.fetchFileChunk(path, offset, size);
                    if (chunk == null) {
                        tempFile.delete();
                        return false;
                    }
                    fos.write(chunk);
                    offset += chunk.length;
                }
            }

            if (targetFile.exists()) {
                targetFile.delete();
            }
            if (!tempFile.renameTo(targetFile)) {
                Files.copy(tempFile.toPath(), targetFile.toPath());
                tempFile.delete();
            }

            targetFile.setLastModified(metadata.lastModified);

            cacheManager.updateCacheEntry(path, metadata.lastModified, metadata.size);
            // Make sure metadata is updated
            cacheManager.updateMetadataCache(path, metadata);

            return true;
        }

        private int pushToServer(String path, File file) throws RemoteException, IOException {
            if (!file.exists()) {
                return Errors.ENOENT;
            }

            long fileSize = file.length();
            long offset = 0;

            try (FileInputStream fis = new FileInputStream(file)) {
                while (offset < fileSize) {
                    int size = (int) Math.min(CacheManager.CHUNK_SIZE, fileSize - offset);
                    byte[] chunk = new byte[size];
                    int read = fis.read(chunk);
                    if (read < size) {
                        if (read <= 0)
                            break;
                        byte[] trimmedChunk = new byte[read];
                        System.arraycopy(chunk, 0, trimmedChunk, 0, read);
                        chunk = trimmedChunk;
                    }

                    int result = remoteServer.pushFileChunk(path, chunk, offset);
                    if (result != 0) {
                        return result;
                    }
                    offset += chunk.length;
                }
            }

            FileMetadata metadata = remoteServer.getFileMetadata(path);
            if (metadata != null && metadata.exists) {
                cacheManager.updateCacheEntry(path, metadata.lastModified, metadata.size);
                // Update metadata cache
                cacheManager.updateMetadataCache(path, metadata);
            }

            return 0;
        }

        @Override
        public int open(String path, OpenOption option) {
            if (DEBUG) {
                System.out.println("open() called with path: " + path + " and option: " + option);
            }

            ReentrantReadWriteLock lock = cacheManager.getFileLock(path);
            String sessionId = null;

            try {
                if (option == OpenOption.READ) {
                    lock.readLock().lock();
                } else {
                    lock.writeLock().lock();
                }

                try {
                    File resolvedFile = resolvePath(path);
                    if (resolvedFile == null) {
                        if (DEBUG) {
                            System.out.println("open(): File not allowed: " + path);
                        }
                        return Errors.ENOENT;
                    }

                    synchronized (openFiles) {
                        for (FileEntry entry : openFiles.values()) {
                            if (entry.relPath.equals(path)) {
                                if (option == OpenOption.READ && entry.mode.equals("r")) {
                                    int fd = nextFd++;
                                    entry.incrementRefCount();
                                    openFiles.put(fd, entry);
                                    return fd;
                                }
                            }
                        }
                    }

                    if (resolvedFile.exists() && resolvedFile.isDirectory()) {
                        if (DEBUG) {
                            System.out.println("open(): Target is a directory: " + resolvedFile);
                        }
                        return Errors.EISDIR;
                    }

                    FileMetadata serverMetadata;
                    try {
                        serverMetadata = remoteServer.getFileMetadata(path);
                        // Cache the metadata
                        cacheManager.updateMetadataCache(path, serverMetadata);
                    } catch (RemoteException e) {
                        if (DEBUG) {
                            System.out.println("open(): Failed to get metadata from server: " + e.getMessage());
                        }
                        return Errors.ENOSYS;
                    }

                    if ((option == OpenOption.READ || option == OpenOption.WRITE)) {
                        if (serverMetadata == null || !serverMetadata.exists) {
                            if (DEBUG) {
                                System.out.println("open(): File does not exist on server: " + path);
                            }
                            return Errors.ENOENT;
                        }

                        boolean needsFetch = !cacheManager.isFileCachedAndUpToDate(path, serverMetadata);

                        if (needsFetch) {
                            if (DEBUG) {
                                System.out.println(
                                        "open(): File not in cache or outdated. Fetching from server: " + path);
                            }

                            try {
                                boolean fetchSuccess = fetchFromServer(path, resolvedFile);
                                if (!fetchSuccess) {
                                    if (DEBUG) {
                                        System.out.println("open(): Failed to fetch file from server: " + path);
                                    }
                                    return Errors.ENOENT;
                                }
                            } catch (IOException e) {
                                if (DEBUG) {
                                    System.out.println("open(): Exception fetching file: " + e.getMessage());
                                }
                                return Errors.ENOSYS;
                            }
                        } else {
                            if (DEBUG) {
                                System.out.println("open(): Using cached file, already up-to-date: " + path);
                            }
                            cacheManager.updateFileUsage(path);
                        }
                        
                        // For READ operations, create a session-specific copy for session semantics
                        if (option == OpenOption.READ) {
                            sessionId = clientId + "-" + System.currentTimeMillis();
                            String sessionPath = path + "." + sessionId + ".session";
                            File sessionFile = new File(cacheManager.cacheDir, sessionPath);
                            
                            if (!sessionFile.exists()) {
                                try {
                                    sessionFile.getParentFile().mkdirs();
                                    Files.copy(resolvedFile.toPath(), sessionFile.toPath());
                                    resolvedFile = sessionFile;
                                    if (DEBUG) {
                                        System.out.println("Created session copy: " + sessionPath);
                                    }
                                } catch (IOException e) {
                                    if (DEBUG) {
                                        System.out.println("Failed to create session copy: " + e.getMessage());
                                    }
                                    // Continue with original file if session copy fails
                                }
                            } else {
                                resolvedFile = sessionFile;
                            }
                        }
                    }

                    if (option == OpenOption.CREATE_NEW
                            && ((serverMetadata != null && serverMetadata.exists) || resolvedFile.exists())) {
                        if (DEBUG) {
                            System.out.println("open(): File already exists for CREATE_NEW: " + path);
                        }
                        return Errors.EEXIST;
                    }

                    if ((option == OpenOption.CREATE || option == OpenOption.CREATE_NEW) && !resolvedFile.exists()) {
                        resolvedFile.getParentFile().mkdirs();
                        try {
                            if (!resolvedFile.createNewFile()) {
                                if (DEBUG) {
                                    System.out.println("open(): Failed to create file: " + resolvedFile);
                                }
                                return Errors.EPERM;
                            }

                            cacheManager.updateCacheEntry(path, System.currentTimeMillis(), 0);

                        } catch (IOException e) {
                            if (DEBUG) {
                                System.out.println("open(): Exception creating file: " + e.getMessage());
                            }
                            return Errors.ENOSYS;
                        }
                    }

                    String mode;
                    switch (option) {
                        case READ:
                            mode = "r";
                            break;
                        case WRITE:
                        case CREATE:
                        case CREATE_NEW:
                            mode = "rw";
                            break;
                        default:
                            if (DEBUG) {
                                System.out.println("open(): Invalid open option: " + option);
                            }
                            return Errors.EINVAL;
                    }

                    long timestamp = serverMetadata != null ? serverMetadata.lastModified : resolvedFile.lastModified();
                    
                    // Mark file as in-use to prevent eviction
                    String actualPath = (sessionId != null) ? (path + "." + sessionId + ".session") : path;
                    cacheManager.markFileInUse(actualPath);

                    return openFile(resolvedFile, mode, path, timestamp, actualPath);
                } finally {
                    if (option == OpenOption.READ) {
                        lock.readLock().unlock();
                    } else {
                        lock.writeLock().unlock();
                    }
                }
            } catch (Exception e) {
                if (DEBUG) {
                    System.out.println("open(): Exception encountered: " + e.getMessage());
                    e.printStackTrace();
                }
                return Errors.ENOSYS;
            }
        }

        private int openFile(File file, String mode, String path, long timestamp, String actualPath) {
            try {
                if (DEBUG) {
                    System.out.println("openFile(): Opening file: " + file + " with mode: " + mode);
                }
                RandomAccessFile raf = new RandomAccessFile(file, mode);
                int fd = nextFd++;
                FileEntry entry = new FileEntry(raf, mode, path, timestamp);
                entry.sessionPath = actualPath;
                openFiles.put(fd, entry);

                cacheManager.updateFileUsage(actualPath);

                if (DEBUG) {
                    System.out.println("openFile(): File opened with fd: " + fd);
                }
                return fd;
            } catch (FileNotFoundException e) {
                if (DEBUG) {
                    System.out.println("openFile(): FileNotFoundException for file: " + file);
                }
                return Errors.ENOENT;
            }
        }

        @Override
        public long read(int fd, byte[] buf) {
            if (DEBUG) {
                System.out.println("read() called with fd: " + fd + " and buffer length: " + buf.length);
            }
            FileEntry entry = openFiles.get(fd);
            if (entry == null) {
                if (DEBUG) {
                    System.out.println("read(): Invalid fd: " + fd);
                }
                return Errors.EBADF;
            }

            ReentrantReadWriteLock lock = cacheManager.getFileLock(entry.relPath);
            lock.readLock().lock();

            try {
                int bytesRead = entry.raf.read(buf);
                if (bytesRead == -1) {
                    if (DEBUG) {
                        System.out.println("read(): End-of-file reached, returning 0.");
                    }
                    return 0;
                }

                cacheManager.updateFileUsage(entry.sessionPath);

                if (DEBUG) {
                    System.out.println("read(): Read " + bytesRead + " bytes from fd " + fd);
                }
                return bytesRead;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("read(): IOException on fd " + fd + ": " + e.getMessage());
                }
                return Errors.ENOSYS;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public long write(int fd, byte[] buf) {
            if (DEBUG) {
                System.out.println("write() called with fd: " + fd + " and buffer length: " + buf.length);
            }
            FileEntry entry = openFiles.get(fd);
            if (entry == null) {
                if (DEBUG) {
                    System.out.println("write(): Invalid fd: " + fd);
                }
                return Errors.EBADF;
            }

            if (!entry.mode.equals("rw")) {
                if (DEBUG) {
                    System.out.println("write(): File not open for writing. Mode: " + entry.mode);
                }
                return Errors.EBADF;
            }

            ReentrantReadWriteLock lock = cacheManager.getFileLock(entry.relPath);
            lock.writeLock().lock();

            try {
                entry.raf.write(buf);
                entry.dirty = true;

                cacheManager.updateFileUsage(entry.sessionPath);

                if (DEBUG) {
                    System.out.println("write(): Wrote " + buf.length + " bytes to fd " + fd);
                }
                return buf.length;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("write(): IOException on fd " + fd + ": " + e.getMessage());
                }
                return Errors.ENOSYS;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public long lseek(int fd, long pos, LseekOption whence) {
            if (DEBUG) {
                System.out.println("lseek() called with fd: " + fd + ", pos: " + pos + ", whence: " + whence);
            }
            FileEntry entry = openFiles.get(fd);
            if (entry == null) {
                if (DEBUG) {
                    System.out.println("lseek(): Invalid fd: " + fd);
                }
                return Errors.EBADF;
            }

            ReentrantReadWriteLock lock = cacheManager.getFileLock(entry.relPath);
            ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
            ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

            boolean isWrite = entry.mode.equals("rw");

            if (isWrite) {
                writeLock.lock();
            } else {
                readLock.lock();
            }

            try {
                long newPos;
                switch (whence) {
                    case FROM_START:
                        newPos = pos;
                        break;
                    case FROM_CURRENT:
                        newPos = entry.raf.getFilePointer() + pos;
                        break;
                    case FROM_END:
                        newPos = entry.raf.length() + pos;
                        break;
                    default:
                        if (DEBUG) {
                            System.out.println("lseek(): Invalid whence: " + whence);
                        }
                        return Errors.EINVAL;
                }

                if (newPos < 0) {
                    if (DEBUG) {
                        System.out.println("lseek(): New position is negative: " + newPos);
                    }
                    return Errors.EINVAL;
                }

                entry.raf.seek(newPos);

                cacheManager.updateFileUsage(entry.sessionPath);

                if (DEBUG) {
                    System.out.println("lseek(): New file pointer for fd " + fd + " is " + newPos);
                }
                return newPos;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("lseek(): IOException on fd " + fd + ": " + e.getMessage());
                }
                return Errors.ENOSYS;
            } finally {
                if (isWrite) {
                    writeLock.unlock();
                } else {
                    readLock.unlock();
                }
            }
        }

        @Override
        public int close(int fd) {
            if (DEBUG) {
                System.out.println("close() called with fd: " + fd);
            }

            FileEntry entry;
            synchronized (openFiles) {
                entry = openFiles.get(fd);
                if (entry == null) {
                    if (DEBUG) {
                        System.out.println("close(): Invalid fd: " + fd);
                    }
                    return Errors.EBADF;
                }

                openFiles.remove(fd);
                if (!entry.decrementRefCount()) {
                    if (DEBUG) {
                        System.out.println("close(): File still has open references, not closing underlying file.");
                    }
                    return 0;
                }
            }
            
            // Mark file as no longer in use
            cacheManager.markFileNotInUse(entry.sessionPath);

            ReentrantReadWriteLock lock = cacheManager.getFileLock(entry.relPath);
            lock.writeLock().lock();

            try {
                entry.raf.close();
                if (DEBUG) {
                    System.out.println("close(): File with fd " + fd + " closed successfully.");
                }

                if (entry.dirty) {
                    if (DEBUG) {
                        System.out.println("close(): Pushing updated file back to remote server.");
                    }

                    try {
                        File file = new File(cacheManager.cacheDir, entry.sessionPath);
                        int ret = pushToServer(entry.relPath, file);
                        if (ret != 0) {
                            if (DEBUG) {
                                System.out.println("close(): Remote push failed with code: " + ret);
                            }
                            return ret;
                        }
                    } catch (Exception e) {
                        if (DEBUG) {
                            System.out.println("close(): Exception pushing file to server: " + e.getMessage());
                        }
                        return Errors.ENOSYS;
                    }
                }
                
                // Clean up session files if needed
                if (!entry.sessionPath.equals(entry.relPath)) {
                    try {
                        File sessionFile = new File(cacheManager.cacheDir, entry.sessionPath);
                        if (sessionFile.exists() && !entry.dirty) {
                            // Only delete if not dirty
                            sessionFile.delete();
                            cacheManager.removeFromCache(entry.sessionPath);
                            if (DEBUG) {
                                System.out.println("Cleaned up session file: " + entry.sessionPath);
                            }
                        }
                    } catch (Exception e) {
                        if (DEBUG) {
                            System.out.println("Exception cleaning up session file: " + e.getMessage());
                        }
                        // Not critical, continue
                    }
                }

                return 0;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("close(): Exception while closing fd " + fd + ": " + e.getMessage());
                }
                return Errors.ENOSYS;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public int unlink(String path) {
            if (DEBUG) {
                System.out.println("unlink() called with path: " + path);
            }

            ReentrantReadWriteLock lock = cacheManager.getFileLock(path);
            lock.writeLock().lock();

            try {
                File resolvedFile;
                try {
                    resolvedFile = resolvePath(path);
                } catch (IOException e) {
                    if (DEBUG) {
                        System.out.println("unlink(): IOException resolving path: " + e.getMessage());
                    }
                    return Errors.ENOSYS;
                }

                if (resolvedFile == null) {
                    if (DEBUG) {
                        System.out.println("unlink(): File not allowed: " + path);
                    }
                    return Errors.ENOENT;
                }

                if (resolvedFile.isDirectory()) {
                    if (DEBUG) {
                        System.out.println("unlink(): Target is a directory: " + resolvedFile);
                    }
                    return Errors.EISDIR;
                }

                synchronized (openFiles) {
                    for (FileEntry entry : openFiles.values()) {
                        if (entry.relPath.equals(path)) {
                            if (DEBUG) {
                                System.out.println("unlink(): File is currently open, but will delete from server");
                            }
                            break;
                        }
                    }
                }

                try {
                    int result = remoteServer.deleteFile(path);
                    if (result != 0) {
                        if (DEBUG) {
                            System.out.println("unlink(): Server delete failed with code: " + result);
                        }
                        return result;
                    }
                } catch (RemoteException e) {
                    if (DEBUG) {
                        System.out.println("unlink(): Remote exception: " + e.getMessage());
                    }
                    return Errors.ENOSYS;
                }
                
                // Clear metadata cache for this file
                cacheManager.updateMetadataCache(path, null); // Clear the cache

                if (!resolvedFile.exists()) {
                    if (DEBUG) {
                        System.out.println("unlink(): File does not exist locally: " + resolvedFile);
                    }
                    return 0;
                }

                if (!resolvedFile.delete()) {
                    if (DEBUG) {
                        System.out.println("unlink(): Failed to delete file locally: " + resolvedFile);
                    }
                    return 0;
                }

                cacheManager.removeFromCache(path);

                if (DEBUG) {
                    System.out.println("unlink(): File deleted successfully: " + resolvedFile);
                }
                return 0;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void clientdone() {
            if (DEBUG) {
                System.out.println("clientdone() called. Cleaning up open files.");
            }
            List<Map.Entry<Integer, FileEntry>> entries;
            synchronized (openFiles) {
                entries = new ArrayList<>(openFiles.entrySet());
            }

            for (Map.Entry<Integer, FileEntry> entry : entries) {
                FileEntry fileEntry = entry.getValue();
                ReentrantReadWriteLock lock = cacheManager.getFileLock(fileEntry.relPath);
                lock.writeLock().lock();
                try {
                    try {
                        fileEntry.raf.close();
                    } catch (IOException e) {
                        if (DEBUG) {
                            System.out.println("clientdone(): IOException while closing file: " + e.getMessage());
                        }
                    }
                    
                    // Mark file as no longer in use
                    cacheManager.markFileNotInUse(fileEntry.sessionPath);

                    if (fileEntry.dirty) {
                        if (DEBUG) {
                            System.out.println("clientdone(): Pushing dirty file: " + fileEntry.relPath);
                        }
                        try {
                            File file = new File(cacheManager.cacheDir, fileEntry.sessionPath);
                            pushToServer(fileEntry.relPath, file);
                        } catch (Exception e) {
                            if (DEBUG) {
                                System.out.println("clientdone(): Exception pushing file: " + e.getMessage());
                            }
                        }
                    }
                    
                    // Clean up session files
                    if (!fileEntry.sessionPath.equals(fileEntry.relPath)) {
                        File sessionFile = new File(cacheManager.cacheDir, fileEntry.sessionPath);
                        if (sessionFile.exists()) {
                            sessionFile.delete();
                            cacheManager.removeFromCache(fileEntry.sessionPath);
                        }
                    }
                } finally {
                    lock.writeLock().unlock();
                }
            }

            // Clear all open files
            synchronized (openFiles) {
                openFiles.clear();
            }

            if (DEBUG) {
                System.out.println("clientdone(): Cleanup complete.");
            }
        }
    }

    private static class FileHandlingFactory implements FileHandlingMaking {
        private final CacheManager cacheManager;
        private final Proxy.RemoteFileServer remoteServer;

        public FileHandlingFactory(CacheManager cacheManager, Proxy.RemoteFileServer remoteServer) {
            this.cacheManager = cacheManager;
            this.remoteServer = remoteServer;
        }

        @Override
        public FileHandling newclient() {
            System.out.println("FileHandlingFactory: Creating new FileHandler instance.");
            return new FileHandler(cacheManager, remoteServer);
        }
    }

    // --- Main method for Proxy ---
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: java Proxy <serverip> <rmiPort> <cachedir> <cachesize>");
            System.exit(1);
        }

        String serverIp = args[0];
        int rmiPort = Integer.parseInt(args[1]);
        String cacheDirPath = args[2];
        long cacheSize = Long.parseLong(args[3]);

        // Prepare the cache directory.
        File cacheDir = new File(cacheDirPath);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            System.err.println("Failed to create cache directory: " + cacheDirPath);
            System.exit(1);
        }

        // Connect to the remote file server via RMI.
        Proxy.RemoteFileServer remoteServer = null;
        try {
            Registry registry = LocateRegistry.getRegistry(serverIp, rmiPort);
            remoteServer = (Proxy.RemoteFileServer) registry.lookup("FileServer");
            System.out.println("Connected to remote server at " + serverIp + ":" + rmiPort);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Initialize the cache manager
        CacheManager cacheManager = new CacheManager(cacheDir, cacheSize);

        // Create the FileHandlingFactory with the cache manager
        FileHandlingFactory factory = new FileHandlingFactory(cacheManager, remoteServer);
        System.out.println("Starting RPCreceiver...");
        try {
            RPCreceiver receiver = new RPCreceiver(factory);
            receiver.run();
        } catch (IOException e) {
            System.err.println("Error starting RPCreceiver: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}