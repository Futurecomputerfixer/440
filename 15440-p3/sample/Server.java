/* Sample code for basic Server */

public class Server {
	public static void main ( String args[] ) throws Exception {
		// Cloud class will start one instance of this Server intially [runs as separate process]
		// It starts another for every startVM call [each a seperate process]
		// Server will be provided 3 command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		
		// Initialize ServerLib.  Almost all server and cloud operations are done 
		// through the methods of this class.  Please refer to the html documentation in ../doc
		ServerLib SL = new ServerLib( args[0], Integer.parseInt(args[1]) );
		// get the VM id for this instance of Server in case we need it
		int myVMid = Integer.parseInt(args[2]);
		
		// register with load balancer so client connections are sent to this server
		SL.register_frontend();
		
		// main loop
		while (true) {
			// wait for and accept next client connection, returns a connection handle
			ServerLib.Handle h = SL.acceptConnection();
			// read and parse request from client connection at the given handle
			Cloud.FrontEndOps.Request r = SL.parseRequest( h );
			// Note: can use the single SL.getNextRequest() call instead of the prior two
			
			// actually process request and send any reply 
			// (this should be a middle tier operation in checkpoints 2 and 3)
			SL.processRequest( r );
		}
	}
}

