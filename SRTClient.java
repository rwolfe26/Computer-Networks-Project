import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Timer;

enum SRTCState {
    CLOSED,
    CONNECTED,
    SYNSENT,
    FINWAIT
}

// -------------------------------
//        SRT CLIENT (PROJECT 4)
// -------------------------------
public class SRTClient {

    private Socket routerSocket;  // Changed from overlaySocket - connects to ROUTER
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    // PROJECT 4: Node IDs for network layer
    private int myNodeID = 77;      // Client node ID from network.dat
    private int serverNodeID = 382; // Server node ID from network.dat

    // PROJECT 4: Graph and routing
    private Graph graph;
    private int nextHopNodeID;      // Next hop router to reach server
    private int nextHopPort;        // Port of next hop router

    private static final int SYN_TIMEOUT = 100;
    private static final int SYN_MAX_RETRY = 5;

    private static final int FIN_TIMEOUT = 100;
    private static final int FIN_MAX_RETRY = 5;

    // Assignment 3 constants
    private static final int GBN_WINDOW = 10;
    private static final int DATA_TIMEOUT = 200;
    private static final int SEND_POLL = 50;

    private static final int MAX_TCB_ENTRIES = 10;
    private TCBClient[] tcbTable;
    private ListenThread listener;

    // -------------------------------
    // INITIALIZATION
    // -------------------------------
    public int initSRTClient() {
        if (inputStream == null) {
            System.err.println("initSRTClient: call startOverlay first");
            return -1;
        }
        tcbTable = new TCBClient[MAX_TCB_ENTRIES];
        listener = new ListenThread(inputStream, tcbTable);
        listener.start();
        System.out.println("SRTClient initialized.");
        return 1;
    }


    // -------------------------------
    // CONNECTION SETUP
    // -------------------------------
    public int connectSRTClient(int sockfd, int serverPort) {
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CLOSED) return -1;

        tcb.portNumServer = serverPort;
        tcb.clientState = SRTCState.SYNSENT;

        SRTSegment syn = new SRTSegment.Builder()
                .srcPort(tcb.portNumClient)
                .destPort(tcb.portNumServer)
                .seqNum(0)
                .type(SRTSegment.Type.SYN)
                .rcvWin(SRTSegment.MAX_SEG_LEN)
                .checksum(0)
                .build();

        for (int i = 0; i < SYN_MAX_RETRY; i++) {
            sendSegment(syn);
            try { Thread.sleep(SYN_TIMEOUT); } catch (Exception ignored) {}
            if (tcb.clientState == SRTCState.CONNECTED) return 1;
        }

        tcb.clientState = SRTCState.CLOSED;
        return -1;
    }


    // -------------------------------
    // DATA SENDING (ASSIGNMENT 3)
    // -------------------------------
    public int sendSRTClient(int sockfd, byte[] data) {
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CONNECTED) return -1;

        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(SRTSegment.MAX_SEG_LEN, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);

            SRTSegment seg = new SRTSegment.Builder()
                    .srcPort(tcb.portNumClient)
                    .destPort(tcb.portNumServer)
                    .seqNum(tcb.nextSeqNum)
                    .type(SRTSegment.Type.DATA)
                    .data(chunk)
                    .rcvWin(SRTSegment.MAX_SEG_LEN)
                    .checksum(0)
                    .build();

            synchronized (tcb.sendBuffer) {
                tcb.sendBuffer.add(new SendBufferNode(seg));
            }

            tcb.nextSeqNum += len;
            offset += len;
        }

        // First time adding data? Start worker
        if (!tcb.workerRunning) {
            tcb.workerRunning = true;
            new Thread(new SendThread(tcb, GBN_WINDOW, DATA_TIMEOUT, SEND_POLL)).start();
        }

        return 1;
    }


    // -------------------------------
    // CONNECTION TEARDOWN
    // -------------------------------
    public int disconnectSRTClient(int sockfd) {
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CONNECTED) return -1;

        tcb.clientState = SRTCState.FINWAIT;

        SRTSegment fin = new SRTSegment.Builder()
                .srcPort(tcb.portNumClient)
                .destPort(tcb.portNumServer)
                .type(SRTSegment.Type.FIN)
                .seqNum(0)
                .rcvWin(SRTSegment.MAX_SEG_LEN)
                .checksum(0)
                .build();

        for (int i = 0; i < FIN_MAX_RETRY; i++) {
            sendSegment(fin);
            try { Thread.sleep(FIN_TIMEOUT); } catch (Exception ignored) {}
            if (tcb.clientState == SRTCState.CLOSED) return 1;
        }

        tcb.clientState = SRTCState.CLOSED;
        return -1;
    }


    public int closeSRTClient(int sockfd) {
        if (sockfd < 0 || sockfd >= MAX_TCB_ENTRIES) return -1;
        TCBClient tcb = tcbTable[sockfd];
        if (tcb == null || tcb.clientState != SRTCState.CLOSED) return -1;

        if (tcb.timer != null) tcb.timer.cancel();
        tcb.sendBuffer.clear();
        tcbTable[sockfd] = null;
        return 1;
    }


    // -------------------------------
    // HELPER: send segment via network layer
    // -------------------------------
    int sendSegment(SRTSegment seg) {
        try {
            // PROJECT 4: Wrap segment in Packet for network layer
            // Packet will be routed through the network to reach serverNodeID
            Packet packet = new Packet(myNodeID, serverNodeID, seg);
            byte[] b = packet.toBytes();

            outputStream.writeInt(b.length);
            outputStream.write(b);
            outputStream.flush();
            return 1;
        } catch (Exception e) {
            System.err.println("Send error: " + e.getMessage());
            return -1;
        }
    }


    // -------------------------------
    // ALLOCATE TCB
    // -------------------------------
    public int createSockSRTClient(int port) {
        for (int i = 0; i < MAX_TCB_ENTRIES; i++) {
            if (tcbTable[i] == null) {
                TCBClient tcb = new TCBClient();
                tcb.portNumClient = port;
                tcb.clientState = SRTCState.CLOSED;
                tcb.timer = new Timer();

                // Assignment 3 additions
                tcb.sendBuffer = new LinkedList<>();
                tcb.nextSeqNum = 0;
                tcb.workerRunning = false;
                tcb.sendBufUnsent = 0;

                tcbTable[i] = tcb;
                return i;
            }
        }
        return -1;
    }


    // -------------------------------
    // PROJECT 4: ROUTING SETUP
    // -------------------------------
    /**
     * Establishes connection to the next-hop ROUTER (not directly to server).
     * Loads network topology, determines next hop, and connects.
     *
     * PROJECT 4 FIX: Client must LISTEN on its network port (like server does)
     * so routers can send responses back to it!
     */
    public int startOverlay(String host, int overlayPort) {
        try {
            // PROJECT 4: Load network topology
            graph = new Graph();
            graph.readNetworkDat("network.dat");
            graph.buildAllNextHopTables();

            // Determine next hop router to reach server
            NetworkNode myNode = graph.getNode(myNodeID);
            if (myNode == null) {
                System.err.println("[SRTClient] Node " + myNodeID + " not found in network.dat");
                return -1;
            }

            nextHopNodeID = myNode.nextHopFor(serverNodeID);
            if (nextHopNodeID == -1) {
                System.err.println("[SRTClient] No route from " + myNodeID + " to " + serverNodeID);
                return -1;
            }

            nextHopPort = graph.getPort(nextHopNodeID);

            // PROJECT 4 FIX: Get client's network port and listen on it
            int myPort = graph.getPort(myNodeID);

            System.out.printf("[SRTClient] Client node %d will listen on port %d for router connections%n",
                    myNodeID, myPort);
            System.out.printf("[SRTClient] Next-hop router to reach server %d is node %d on port %d%n",
                    serverNodeID, nextHopNodeID, nextHopPort);

            // Start listening for incoming connections from routers (for responses)
            ServerSocket listenSocket = new ServerSocket(myPort);
            System.out.printf("[SRTClient] Listening on port %d for incoming routed packets...%n", myPort);

            // Start a thread to accept router connections
            Thread acceptThread = new Thread(() -> {
                try {
                    while (true) {
                        Socket incomingSocket = listenSocket.accept();
                        System.out.printf("[SRTClient] Accepted connection from router%n");

                        // Handle this connection for incoming packets
                        DataInputStream inStream = new DataInputStream(incomingSocket.getInputStream());
                        Thread incomingHandler = new Thread(() -> {
                            try {
                                while (true) {
                                    int len = inStream.readInt();
                                    byte[] buf = new byte[len];
                                    inStream.readFully(buf);

                                    // Parse incoming packet
                                    Packet packet = Packet.fromBytes(buf);
                                    SRTSegment seg = packet.seg;

                                    System.out.printf("[SRTClient] Received %s from network (src=%d->dest=%d)%n",
                                            seg.type, packet.srcNodeID, packet.destNodeID);

                                    // Find appropriate TCB and process
                                    for (int i = 0; i < tcbTable.length; i++) {
                                        TCBClient tcb = tcbTable[i];
                                        if (tcb != null && tcb.portNumClient == seg.destPort &&
                                                tcb.portNumServer == seg.srcPort) {

                                            switch (seg.type) {
                                                case SYNACK:
                                                    System.out.println("[SRTClient] SYNACK received! Connection established.");
                                                    tcb.clientState = SRTCState.CONNECTED;
                                                    break;
                                                case FINACK:
                                                    tcb.clientState = SRTCState.CLOSED;
                                                    break;
                                                case DATAACK:
                                                    handleDataAckDirect(tcb, seg.seqNum);
                                                    break;
                                            }
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Connection closed
                            }
                        });
                        incomingHandler.setDaemon(true);
                        incomingHandler.start();
                    }
                } catch (Exception e) {
                    System.err.println("[SRTClient] Accept thread error: " + e.getMessage());
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            // Small delay to ensure we're listening before connecting out
            Thread.sleep(500);

            // PROJECT 4: Connect to ROUTER, not to server
            routerSocket = new Socket("localhost", nextHopPort);
            outputStream = new DataOutputStream(routerSocket.getOutputStream());
            inputStream = new DataInputStream(routerSocket.getInputStream());

            SRTClientHolder.client = this;

            System.out.printf("[SRTClient] Connected to router %d, will send packets destined for server %d%n",
                    nextHopNodeID, serverNodeID);

            return 1;
        } catch (Exception e) {
            System.err.println("[SRTClient] startOverlay error: " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

    // Helper method for handling DATAACK from incoming connections
    private void handleDataAckDirect(TCBClient tcb, int ackNum) {
        int removed = 0;
        synchronized (tcb.sendBuffer) {
            while (!tcb.sendBuffer.isEmpty()) {
                SendBufferNode head = tcb.sendBuffer.peekFirst();
                int start = head.segment.seqNum;
                int end = start + head.segment.length;
                if (end <= ackNum) {
                    tcb.sendBuffer.removeFirst();
                    removed++;
                } else break;
            }
            tcb.sendBufUnsent = Math.max(0, tcb.sendBufUnsent - removed);
        }
    }

    public int stopOverlay() {
        try {
            if (listener != null) {listener.stopRunning(); listener.join(200); }
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (routerSocket != null) routerSocket.close();
            return 1;
        } catch (Exception e) {
            return -1;
        }
    }
}



// ===============================
// LISTENER THREAD
// ===============================
class ListenThread extends Thread {

    private final DataInputStream inputStream;
    private final TCBClient[] tcbTable;
    private volatile boolean running = true;

    public ListenThread(DataInputStream in, TCBClient[] table) {
        this.inputStream = in;
        this.tcbTable = table;
    }

    public void run() {
        while (running) {
            try {
                int len = inputStream.readInt();
                byte[] buf = new byte[len];
                inputStream.readFully(buf);

                // PROJECT 4: Incoming data is wrapped in Packet
                Packet packet = Packet.fromBytes(buf);
                SRTSegment seg = packet.seg;

                int idx = findTCB(seg.destPort, seg.srcPort);
                if (idx == -1) continue;

                TCBClient tcb = tcbTable[idx];

                switch (seg.type) {
                    case SYNACK:
                        tcb.clientState = SRTCState.CONNECTED;
                        break;

                    case FINACK:
                        tcb.clientState = SRTCState.CLOSED;
                        break;

                    case DATAACK:
                        handleDataAck(tcb, seg.seqNum);
                        break;

                    default:
                        break;
                }

            } catch (Exception e) {
                running = false;
            }
        }
    }

    private void handleDataAck(TCBClient tcb, int ackNum) {
        int removed = 0;
        synchronized (tcb.sendBuffer) {
            while (!tcb.sendBuffer.isEmpty()) {
                SendBufferNode head = tcb.sendBuffer.peekFirst();
                int start = head.segment.seqNum;
                int end = start + head.segment.length;
                if (end <= ackNum) {
                    tcb.sendBuffer.removeFirst();
                    removed++;
                } else break;
            }
            tcb.sendBufUnsent = Math.max(0, tcb.sendBufUnsent - removed);
        }
    }

    private int findTCB(int destPort, int srcPort) {
        for (int i = 0; i < tcbTable.length; i++) {
            TCBClient t = tcbTable[i];
            if (t != null && t.portNumClient == destPort && t.portNumServer == srcPort)
                return i;
        }
        return -1;
    }

    public void stopRunning() {
        running = false;
    }
}



// ===============================
// SEND BUFFER NODE
// ===============================
class SendBufferNode {
    SRTSegment segment;
    long sentTime;

    SendBufferNode(SRTSegment seg) {
        this.segment = seg;
        this.sentTime = 0;
    }
}



// ===============================
// SEND THREAD (GBN)
// ===============================
class SendThread implements Runnable {

    private final TCBClient tcb;
    private final int win, timeoutMs, pollMs;
    public SendThread(TCBClient t, int win, int timeoutMs, int pollMs) {
        this.tcb = t;
        this.win = win;
        this.timeoutMs = timeoutMs;
        this.pollMs = pollMs;
    }

    @Override
    public void run() {
        while (tcb.clientState == SRTCState.CONNECTED && !tcb.sendBuffer.isEmpty()) {

            synchronized (tcb.sendBuffer) {
                while (tcb.sendBufUnsent < Math.min(tcb.sendBuffer.size(), win)) {

                    SendBufferNode node = tcb.sendBuffer.get(tcb.sendBufUnsent);
                    if (node.sentTime == 0){
                        // Send the DATA segment via network layer
                        SRTClientHolder.client.sendSegment(node.segment);
                        node.sentTime = System.currentTimeMillis();
                        tcb.sendBufUnsent++;
                    } else break;
                }
            }

            try { Thread.sleep(50); } catch (Exception ignored) {}

            synchronized (tcb.sendBuffer) {
                if (!tcb.sendBuffer.isEmpty() && tcb.sendBufUnsent > 0) {
                    SendBufferNode oldest = tcb.sendBuffer.getFirst();
                    long age = System.currentTimeMillis() - oldest.sentTime;
                    if (age >= timeoutMs) {
                        // timeout → resend all
                        for (int i = 0; i < tcb.sendBufUnsent; i++) {
                            SendBufferNode node = tcb.sendBuffer.get(i);
                            SRTClientHolder.client.sendSegment(node.segment);
                            node.sentTime = System.currentTimeMillis();
                        }
                    }
                } else if (tcb.sendBuffer.isEmpty()) {
                    break;
                }
            }
            try { Thread.sleep(pollMs); } catch (Exception ignored) {}
        }
        tcb.workerRunning = false;
    }
}



// ===============================
// GLOBAL CLIENT HANDLE
// ===============================
class SRTClientHolder {
    public static SRTClient client;
}



// ===============================
// EXTENDED TCB
// ===============================
class TCBClient {
    int nodeIDServer;
    int portNumServer;
    int nodeIDClient;
    int portNumClient;
    volatile SRTCState clientState;
    Timer timer;

    // Assignment 3 fields:
    LinkedList<SendBufferNode> sendBuffer;
    int nextSeqNum;
    int sendBufUnsent;
    boolean workerRunning;
}