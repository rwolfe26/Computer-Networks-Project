import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Timer;

enum SRTCState{
    CLOSED,
    CONNECTED,
    SYNSENT,
    FINWAIT
}



public class SRTClient {

    private Socket overlaySocket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    private ListenThread listenThread;

    private static final int SYN_TIMEOUT = 100;
    private static final int SYN_MAX_RETRY = 5;

    private static final int FIN_TIMEOUT = 100;
    private static final int FIN_MAX_RETRY = 5;



    private static final int MAX_TCB_ENTRIES = 10;
    private TCBClient[] tcbTable; // array to host TCB clients for each Socket connection


    public int initSRTClient() {
        if (inputStream == null) {
            System.err.println("initSRTClient: overlay not started; call startOverlay first");
            return -1;
        }
        try {
            // make sure tcbTable is initialized and all entries are null
            tcbTable = new TCBClient[MAX_TCB_ENTRIES];
            for (int i = 0; i < MAX_TCB_ENTRIES; i++) {
                tcbTable[i] = null;
            }

            listenThread = new ListenThread(inputStream, tcbTable);
            listenThread.start();

            System.out.println("SRTClient initialized with " + MAX_TCB_ENTRIES + " entries");
            return 1;
        } catch (Exception e) {
            System.err.println("Could not initialize SRTClient: " + e.getMessage());
            return -1;
        }
    }

    public int connectSRTClient(int sockfd, int serverPort) {
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CLOSED) {
            System.err.println("Error connecting SRTClient(no TCBClient found or not closed): " + sockfd);
            return -1;
        }

        tcb.portNumServer = serverPort;
        tcb.clientState = SRTCState.SYNSENT;

        SRTSegment syn = new SRTSegment.Builder()
                .srcPort(tcb.portNumClient)
                .destPort(tcb.portNumServer)
                .seqNum(0)
                .type(SRTSegment.Type.SYN)
                .rcvWin(SRTSegment.MAX_SEG_LEN) // not sure if this breaks it?
                .checksum(0)
                .build();

        for (int attempt = 0; attempt < SYN_MAX_RETRY; attempt++) {
            System.out.println("Sending SYN to server at port " + serverPort + " attempt " + attempt);
            if (sendSegment(syn) == -1) {
                System.err.println("Error sending SYN to server at port " + serverPort + " attempt " + attempt);
                continue;
            }

            try{
                Thread.sleep(SYN_TIMEOUT);
            } catch (InterruptedException ignored) {
            }
            if (tcb.clientState == SRTCState.CONNECTED) {
                System.out.println("SYNACK Received, Connection Established. To Server Port:" + serverPort);
                return 1;
            }
        }

        System.err.println("Could not connect to server at port " + serverPort);
        tcb.clientState = SRTCState.CLOSED;
        return -1;
    }

    public int disconnectSRTClient(int sockfd) {
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CONNECTED) {
            System.err.println("[SRTClient] Invalid socket or state for disconnect.");
            return -1;
        }

        tcb.clientState = SRTCState.FINWAIT;

        SRTSegment fin = new SRTSegment.Builder()
                .srcPort(tcb.portNumClient)
                .destPort(tcb.portNumServer)
                .seqNum(0)
                .type(SRTSegment.Type.FIN)
                .rcvWin(SRTSegment.MAX_SEG_LEN)
                .checksum(0)
                .build();

        for (int attempt = 1; attempt <= FIN_MAX_RETRY; attempt++) {
            System.out.println("Sending FIN (attempt " + attempt + ")");
            if (sendSegment(fin) == -1) {
                System.err.println("Failed to send FIN segment.");
                continue;
            }

            try {
                Thread.sleep(FIN_TIMEOUT);
            } catch (InterruptedException ignored) {}

            if (tcb.clientState == SRTCState.CLOSED) {
                System.out.println("FINACK received. Connection closed.");
                return 1;
            }
        }

        System.err.println("FIN retries exhausted. Connection may not have closed cleanly.");
        tcb.clientState = SRTCState.CLOSED;
        return -1;
    }

    public int closeSRTClient(int sockfd) {
        //Check sockfd is valid
        if (sockfd < 0 || sockfd >= MAX_TCB_ENTRIES) {
            System.err.println("[SRTClient] Invalid socket ID: " + sockfd);
            return -1;
        }

        // get TCBClient for this socket
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null) {
            System.err.println("[SRTClient] No TCB found for socket ID " + sockfd);
            return -1;
        }

        if (tcb.clientState != SRTCState.CLOSED) {
            System.err.println("[SRTClient] Socket " + sockfd + " not in CLOSED state. Cannot close yet.");
            return -1;
        }

        if (tcb.timer != null) {
            tcb.timer.cancel();
        }

        tcbTable[sockfd] = null;

        System.out.println("[SRTClient] Closed and released socket ID " + sockfd);
        return 1;
    }


    private int sendSegment(SRTSegment segment){
        try{
            byte[] encodedSegment = segment.toBytes();
            outputStream.writeInt(encodedSegment.length);
            outputStream.write(encodedSegment);
            outputStream.flush();
            return 1;
        } catch (IOException e) {
            System.err.println("Error sending SRTSegment: " + e.getMessage());
            return -1;
        }
    }


    public int createSockSRTClient(int clientPort) {
        for (int i = 0; i < MAX_TCB_ENTRIES; i++) {
            if (tcbTable[i] == null) {
                TCBClient tcb = new TCBClient();
                tcb.nodeIDServer = 0;
                tcb.portNumServer = 0;
                tcb.nodeIDClient = 0;
                tcb.portNumClient = clientPort;
                tcb.clientState = SRTCState.CLOSED;
                tcb.timer = new Timer();
                tcbTable[i] = tcb;
                System.out.println("TCBClient created for client port " + clientPort);
                return i; // return index of TCBClient in tcbTable
            }
        }
        System.err.println("Could not create TCBClient for client port " + clientPort);
        return -1;
    }


    // Pass "localhost" and port number as arguments
    public int startOverlay(String host, int port){
        try{
            // Use host name to resolve Server IP address
            InetAddress ip = InetAddress.getByName(host);

            // Open TCP socket connection to server using resolved IP address
            overlaySocket = new Socket(ip, port);

            // Create input and output streams for the socket connection
            outputStream = new DataOutputStream(overlaySocket.getOutputStream());
            inputStream = new DataInputStream(overlaySocket.getInputStream());

            System.out.println("Connected to " + host + ":" + port);
            return 1;

        } catch (IOException e) {
            System.err.println("Could not connect to " + host + ":" + port);
            return -1;
        }
    }

    public int stopOverlay() {
        try {
            if (listenThread != null) {
                listenThread.stopRunning();
                try {
                    listenThread.join(200);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (overlaySocket != null) overlaySocket.close();

            System.out.println("SRTClient Connection is closed.");
            return 1;
        } catch (IOException e) {
            System.err.println("Could not close SRTClient connection: " + e.getMessage());
            return -1;
        }
    }
}


class ListenThread extends Thread {
    private final DataInputStream inputStream;
    private final TCBClient[] tcbTable;
    private volatile boolean running = true; // Thread safe flag for run loop

    public ListenThread(DataInputStream inputStream, TCBClient[] tcbTable) {
        this.inputStream = inputStream;
        this.tcbTable = tcbTable;
    }

    public void run() {
        while (running) {
            try {
                int segmentLength = inputStream.readInt();
                byte[] buffer = new byte[segmentLength];
                inputStream.readFully(buffer);

                // parse the buffer into a SRTSegment object
                SRTSegment srtSegment = SRTSegment.fromBytes(buffer);

                //Identify which TCBClient this segment belongs to
                int tcbIndex = findTCB(srtSegment.destPort, srtSegment.srcPort);
                if (tcbIndex == -1) {
                    System.err.println("Error reading from SRTClient(no TCBClient found): " + srtSegment.destPort + " " + srtSegment.srcPort);
                    continue;
                }
                switch (srtSegment.type) {
                    case SYNACK:
                        tcbTable[tcbIndex].clientState = SRTCState.CONNECTED;
                        System.out.println("[Listener] Received SYNACK → connection established for socket " + tcbIndex);
                        break;

                    case FINACK:
                        tcbTable[tcbIndex].clientState = SRTCState.CLOSED;
                        System.out.println("[Listener] Received FINACK → connection closed for socket " + tcbIndex);
                        break;

                    default:
                        System.out.println("[Listener] Ignored segment type " + srtSegment.type);
                        break;
                }

            } catch (IOException e) {
                if (running){
                System.err.println("Error reading from SRTClient(IO error): " + e.getMessage());
                }
                running = false;
            } catch (Exception e) {
                if (running){
                System.err.println("Error reading from SRTClient(parsing error): " + e.getMessage());
                }
                running = false;
            }
        }

        System.out.println("ListenThread stopped.");
    }


    private int findTCB(int destPort, int srcPort){
        for (int i = 0; i < tcbTable.length; i++) {
            TCBClient tcb = tcbTable[i];
            if (tcb != null && tcb.portNumClient == destPort && tcb.portNumServer == srcPort) {
                return i;
            }
        }
        return -1;
    }

    public void stopRunning() {
        running = false;
    }



}




class TCBClient {
    int nodeIDServer;
    int portNumServer;
    int nodeIDClient;
    int portNumClient;
    volatile SRTCState clientState;
    Timer timer;

}

