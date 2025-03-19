import java.rmi.Remote;
import java.rmi.RemoteException;

public interface FileServer extends Remote {
    byte[] fetchFile(String path) throws RemoteException;
    int pushFile(String path, byte[] data) throws RemoteException;
}
