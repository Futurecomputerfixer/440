import java.io.*;
import java.nio.file.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Server extends UnicastRemoteObject implements Proxy.RemoteFileServer {
    private final File rootDir;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();
    private static final boolean DEBUG = true;
    private static final int CHUNK_SIZE = 50000; // 50KB chunks for file transfer
    
    // Define error codes to match the ones in Proxy.FileHandling.Errors
    private static class Errors {
        public static final int ENOENT = -2;  // No such file or directory
        public static final int ENOSYS = -38; // Function not implemented
        public static final int EPERM = -1;   // Operation not permitted
        public static final int EISDIR = -21; // Is a directory
    }

    protected Server(File rootDir) throws RemoteException {
        super();
        this.rootDir = rootDir.getAbsoluteFile();
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
    }

    private ReentrantReadWriteLock getFileLock(String path) {
        return fileLocks.computeIfAbsent(path, k -> new ReentrantReadWriteLock());
    }

    @Override
    public Proxy.FileMetadata getFileMetadata(String path) throws RemoteException {
        if (DEBUG) {
            System.out.println("getFileMetadata() called with path: " + path);
        }
        
        ReentrantReadWriteLock lock = getFileLock(path);
        lock.readLock().lock();
        
        try {
            File file = resolvePath(path);
            if (file == null) {
                if (DEBUG) {
                    System.out.println("getFileMetadata(): File path invalid: " + path);
                }
                return new Proxy.FileMetadata(0, 0, false);
            }
            
            boolean exists = file.exists() && file.isFile();
            long size = exists ? file.length() : 0;
            long lastModified = exists ? file.lastModified() : 0;
            
            if (DEBUG) {
                System.out.println("getFileMetadata(): " + path + " exists=" + exists + 
                        ", size=" + size + ", lastModified=" + lastModified);
            }
            
            return new Proxy.FileMetadata(size, lastModified, exists);
        } catch (IOException e) {
            if (DEBUG) {
                System.out.println("Error getting file metadata: " + e.getMessage());
                e.printStackTrace();
            }
            throw new RemoteException("Error getting file metadata", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public byte[] fetchFileChunk(String path, long offset, int chunkSize) throws RemoteException {
        if (DEBUG) {
            System.out.println("fetchFileChunk() called with path: " + path + 
                    ", offset: " + offset + ", chunkSize: " + chunkSize);
        }
        
        ReentrantReadWriteLock lock = getFileLock(path);
        lock.readLock().lock();
        
        try {
            File file = resolvePath(path);
            if (file == null || !file.exists() || !file.isFile()) {
                if (DEBUG) {
                    System.out.println("fetchFileChunk(): File not found or invalid: " + path);
                }
                return null;
            }
            
            long fileSize = file.length();
            if (offset >= fileSize) {
                if (DEBUG) {
                    System.out.println("fetchFileChunk(): Offset beyond end of file: " + offset + " >= " + fileSize);
                }
                return new byte[0]; // End of file reached
            }
            
            // Adjust chunk size if it goes beyond the end of the file
            int actualChunkSize = (int) Math.min(chunkSize, fileSize - offset);
            byte[] chunk = new byte[actualChunkSize];
            
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                int bytesRead = raf.read(chunk);
                
                if (bytesRead < actualChunkSize) {
                    // If we read less than expected, adjust the array size
                    if (bytesRead <= 0) {
                        return new byte[0]; // No data read
                    }
                    
                    byte[] resizedChunk = new byte[bytesRead];
                    System.arraycopy(chunk, 0, resizedChunk, 0, bytesRead);
                    chunk = resizedChunk;
                }
                
                if (DEBUG) {
                    System.out.println("fetchFileChunk(): Read " + chunk.length + " bytes from " + path);
                }
                
                return chunk;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("fetchFileChunk(): IOException: " + e.getMessage());
                    e.printStackTrace();
                }
                throw new RemoteException("Error reading file", e);
            }
        } catch (IOException e) {
            throw new RemoteException("Error resolving path", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int pushFileChunk(String path, byte[] data, long offset) throws RemoteException {
        if (DEBUG) {
            System.out.println("pushFileChunk() called with path: " + path + 
                    ", offset: " + offset + ", data length: " + data.length);
        }
        
        ReentrantReadWriteLock lock = getFileLock(path);
        lock.writeLock().lock();
        
        try {
            File file;
            try {
                file = resolvePath(path);
                if (file == null) {
                    if (DEBUG) {
                        System.out.println("pushFileChunk(): Invalid file path: " + path);
                    }
                    return Errors.ENOENT;
                }
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("pushFileChunk(): Error resolving path: " + e.getMessage());
                }
                return Errors.ENOSYS;
            }
            
            // Ensure parent directories exist
            file.getParentFile().mkdirs();
            
            // If this is the first chunk (offset 0) and we're not appending, create a new file
            if (offset == 0) {
                // If file already exists, we'll overwrite it
                if (!file.exists()) {
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        if (DEBUG) {
                            System.out.println("pushFileChunk(): Failed to create file: " + e.getMessage());
                        }
                        return Errors.EPERM;
                    }
                }
            } else if (!file.exists()) {
                // If we're trying to write at an offset but the file doesn't exist yet
                if (DEBUG) {
                    System.out.println("pushFileChunk(): Cannot write at offset to non-existent file");
                }
                return Errors.ENOENT;
            }
            
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                if (offset > raf.length()) {
                    // If there's a gap, pad with zeros
                    raf.seek(raf.length());
                    long padding = offset - raf.length();
                    byte[] zeros = new byte[(int)Math.min(padding, 8192)];
                    for (long i = 0; i < padding; i += zeros.length) {
                        int writeSize = (int)Math.min(zeros.length, padding - i);
                        raf.write(zeros, 0, writeSize);
                    }
                }
                
                raf.seek(offset);
                raf.write(data);
                
                // Set the last modified time to reflect the update
                file.setLastModified(System.currentTimeMillis());
                
                if (DEBUG) {
                    System.out.println("pushFileChunk(): Wrote " + data.length + " bytes to " + path + " at offset " + offset);
                }
                
                return 0;
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("pushFileChunk(): IOException: " + e.getMessage());
                    e.printStackTrace();
                }
                return Errors.ENOSYS;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public int deleteFile(String path) throws RemoteException {
        if (DEBUG) {
            System.out.println("deleteFile() called with path: " + path);
        }
        
        ReentrantReadWriteLock lock = getFileLock(path);
        lock.writeLock().lock();
        
        try {
            File file;
            try {
                file = resolvePath(path);
                if (file == null) {
                    if (DEBUG) {
                        System.out.println("deleteFile(): Invalid file path: " + path);
                    }
                    return Errors.ENOENT;
                }
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("deleteFile(): Error resolving path: " + e.getMessage());
                }
                return Errors.ENOSYS;
            }
            
            if (!file.exists()) {
                if (DEBUG) {
                    System.out.println("deleteFile(): File does not exist: " + path);
                }
                // Consider non-existence a success for idempotency
                return 0; 
            }
            
            if (file.isDirectory()) {
                if (DEBUG) {
                    System.out.println("deleteFile(): Path is a directory: " + path);
                }
                return Errors.EISDIR;
            }
            
            if (file.delete()) {
                if (DEBUG) {
                    System.out.println("deleteFile(): Successfully deleted: " + path);
                }
                return 0;
            } else {
                if (DEBUG) {
                    System.out.println("deleteFile(): Failed to delete: " + path);
                }
                return Errors.EPERM;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Resolves the given path and ensures it is under the root directory.
    private File resolvePath(String path) throws IOException {
        File file = new File(rootDir, path).getCanonicalFile();
        if (!file.getPath().startsWith(rootDir.getCanonicalPath())) {
            if (DEBUG) {
                System.out.println("resolvePath(): Path escapes root directory: " + path);
            }
            return null;
        }
        return file;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java Server <port> <rootdir>");
            System.exit(1);
        }
        
        int port = Integer.parseInt(args[0]);
        File rootDir = new File(args[1]);
        
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            System.err.println("Failed to create root directory: " + rootDir);
            System.exit(1);
        }
        
        try {
            Server server = new Server(rootDir);
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind("FileServer", server);
            System.out.println("Server ready on port " + port + " serving files from " + rootDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }
}