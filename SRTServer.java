import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;


public class SRTServer {
    // states of the server
    public static final int S_CLOSED=1, S_LISTENING=2, S_CONNECTED=3, S_CLOSEWAIT=4;
    public static final int CLOSE_WAIT_TIMEOUT_MS = 1000;
    private static final int RECVBUF_SIZE = 1024 * 1024;

    private int myNodeId;
    private int clientNodeId;  // Renamed from remoteNodeId for clarity

    // PROJECT 4: Graph and routing
    private Graph graph;
    private int nextHopNodeID;  // Next hop router to reach client
    private int nextHopPort;    // Port of next hop router

    public SRTServer(int myNodeId, int clientNodeId) {
        this.myNodeId = myNodeId;
        this.clientNodeId = clientNodeId;
    }

    public SRTServer(){
        this(382, 77); // server node = 382, client = 77 from the network.dat
    }

    // Router connection (instead of direct overlay to client)
    private ServerSocket routerListenSocket;  // Listen for connection FROM router
    private Socket routerSocket;              // Connection to router
    private DataInputStream in;
    private DataOutputStream out;

    //Listener
    private Thread listener;
    private volatile boolean running;

    public static class TCB {
        int portServer, portClient;
        volatile int state = S_CLOSED;
        int expectSeqNum;
        byte[] recvBuf;
        int usedLen;
    }

    private final TCB[] table = new TCB[32];

    // -------------------------------
    // PROJECT 4: ROUTING SETUP
    // -------------------------------
    /**
     * Starts listening on the server's network port for connections FROM the router.
     * The router will forward packets from the client to this server.
     */
    public int startOverlay(int myPort) {
        try {
            // PROJECT 4: Load network topology
            graph = new Graph();
            graph.readNetworkDat("network.dat");
            graph.buildAllNextHopTables();

            // Determine which router will connect to us (next hop from server to client)
            NetworkNode myNode = graph.getNode(myNodeId);
            if (myNode == null) {
                System.err.println("[SRTServer] Node " + myNodeId + " not found in network.dat");
                return -1;
            }

            nextHopNodeID = myNode.nextHopFor(clientNodeId);
            if (nextHopNodeID == -1) {
                System.err.println("[SRTServer] No route from " + myNodeId + " to " + clientNodeId);
                return -1;
            }

            nextHopPort = graph.getPort(nextHopNodeID);

            // Get our port from network.dat
            int serverPort = graph.getPort(myNodeId);

            System.out.printf("[SRTServer] Server node %d listening on port %d for router connections%n",
                    myNodeId, serverPort);
            System.out.printf("[SRTServer] Next-hop router to reach client %d is node %d on port %d%n",
                    clientNodeId, nextHopNodeID, nextHopPort);

            // PROJECT 4: Listen on our network port for ROUTER to connect
            routerListenSocket = new ServerSocket(serverPort);
            routerSocket = routerListenSocket.accept();

            System.out.printf("[SRTServer] Accepted connection from router (likely node %d)%n", nextHopNodeID);

            out = new DataOutputStream(new BufferedOutputStream(routerSocket.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(routerSocket.getInputStream()));

            return 1;
        } catch (Exception e) {
            System.err.println("[SRTServer] startOverlay error: " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

    public int stopOverlay(){
        running = false;
        try { if (listener != null) listener.join(200); } catch (InterruptedException ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (routerSocket != null) routerSocket.close(); } catch (Exception ignored) {}
        try { if (routerListenSocket != null) routerListenSocket.close(); } catch (Exception ignored) {}
        return 1;
    }


    // The srt API
    public int init() {
        Arrays.fill(table, null);
        startListener();
        return 1;
    }

    public int createSock(int serverPort) {
        for (int i=0; i<table.length; i++) if (table[i]==null) {
            TCB t = new TCB();
            t.portServer = serverPort;
            t.state = S_CLOSED;

            t.expectSeqNum = 0;
            t.recvBuf = new byte[RECVBUF_SIZE];
            t.usedLen = 0;
            table[i] = t;
            return i;
        }
        return -1;
    }


    public int accept(int sockid) {
        if (!valid(sockid)) return -1;
        TCB t = table[sockid];
        t.state = S_LISTENING;

        long start = System.currentTimeMillis();
        while (t.state != S_CONNECTED) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            if (System.currentTimeMillis() - start > 10000) return -1;
        }
        return 1;
    }


    public int close(int sockid) {
        if (!valid(sockid)) return -1;
        table[sockid] = null;
        return 1;
    }


    // -------------------------------
    // LISTENER THREAD
    // -------------------------------
    private void startListener() {
        running = true;
        listener = new Thread(() -> {
            try {
                while (running) {
                    int frameLen;
                    try {
                        frameLen = in.readInt();          // length prefix
                    } catch (EOFException eof) {
                        System.out.println("[SRTServer] Router disconnected (EOF).");
                        break;
                    }
                    if (frameLen <= 0) continue;

                    byte[] buf = new byte[frameLen];
                    in.readFully(buf);

                    // PROJECT 4: Read as Packet (routed through network layer)
                    Packet pkt = Packet.fromBytes(buf);
                    SRTSegment seg = pkt.seg;

                    System.out.printf(
                            "[SRTServer] Received %s packet (net: src=%d->dest=%d, seg: srcPort=%d->destPort=%d, len=%d)%n",
                            (seg == null ? "null" : seg.type),
                            pkt.srcNodeID,
                            pkt.destNodeID,
                            (seg == null ? -1 : seg.srcPort),
                            (seg == null ? -1 : seg.destPort),
                            (seg == null ? 0 : seg.length)
                    );

                    if (seg == null) {
                        continue;
                    }

                    // Verify packet is for us
                    if (pkt.destNodeID != myNodeId) {
                        System.err.printf("[SRTServer] Warning: received packet destined for node %d, but we are node %d%n",
                                pkt.destNodeID, myNodeId);
                    }

                    // Route by server's SRT port
                    TCB t = findbyServerPort(seg.destPort);
                    if (t == null) {
                        System.out.printf("[SRTServer] No TCB listening on port %d%n", seg.destPort);
                        continue;
                    }

                    switch (seg.type) {
                        case SYN:
                            System.out.printf("[SRTServer] SYN received on %d; current state=%d%n",
                                    t.portServer, t.state);
                            if (t.state == S_LISTENING) {
                                t.portClient = seg.srcPort;
                                t.state = S_CONNECTED;

                                SRTSegment synack = new SRTSegment.Builder()
                                        .type(SRTSegment.Type.SYNACK)
                                        .srcPort(t.portServer)
                                        .destPort(t.portClient)
                                        .build();
                                send(synack, pkt.srcNodeID);  // Send back to client's node
                                System.out.printf("[SRTServer] Sent SYNACK (destNode=%d) to client port %d%n",
                                        pkt.srcNodeID, t.portClient);
                            } else {
                                // Already connected - just resend SYNACK (retransmission)
                                System.out.printf("[SRTServer] Retransmitting SYNACK (state=%d)%n", t.state);
                                SRTSegment synack = new SRTSegment.Builder()
                                        .type(SRTSegment.Type.SYNACK)
                                        .srcPort(t.portServer)
                                        .destPort(t.portClient)
                                        .build();
                                send(synack, pkt.srcNodeID);
                            }
                            break;

                        case FIN:
                            System.out.printf("[SRTServer] FIN received on %d; current state=%d%n",
                                    t.portServer, t.state);
                            if (t.state == S_CONNECTED) {
                                SRTSegment finack = new SRTSegment.Builder()
                                        .type(SRTSegment.Type.FINACK)
                                        .srcPort(t.portServer)
                                        .destPort(t.portClient)
                                        .build();
                                send(finack, pkt.srcNodeID);  // Send back to client's node
                                System.out.printf("[SRTServer] Sent FINACK to client %d%n", t.portClient);

                                t.state = S_CLOSEWAIT;
                                new Timer(true).schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        t.state = S_CLOSED;
                                        System.out.printf("[SRTServer] Connection on port %d fully CLOSED.%n",
                                                t.portServer);
                                    }
                                }, CLOSE_WAIT_TIMEOUT_MS);
                            }
                            break;

                        case DATA:
                            System.out.printf("[SRTServer] DATA received (%d bytes) from %d%n",
                                    seg.length, seg.srcPort);
                            // accept if the DATA starts at expectSeqNum
                            if (seg.seqNum == t.expectSeqNum){
                                if (t.usedLen + seg.length <= RECVBUF_SIZE) {
                                    System.arraycopy(seg.data, 0, t.recvBuf, t.usedLen, seg.length);
                                    t.usedLen += seg.length;
                                    t.expectSeqNum += seg.length;
                                    System.out.printf("[SRTServer] Accepted DATA; expectSeqNum now %d, usedLen=%d%n",
                                            t.expectSeqNum, t.usedLen);
                                } else {
                                    System.out.println("[SRTServer] Receive buffer full, dropping DATA payload");
                                }
                            } else {
                                System.out.printf("[SRTServer] Out of order DATA (got seq=%d, expect=%d) -> drop%n",
                                        seg.seqNum, t.expectSeqNum);
                            }
                            SRTSegment dataAck = new SRTSegment.Builder()
                                    .type(SRTSegment.Type.DATAACK)
                                    .srcPort(t.portServer)
                                    .destPort(t.portClient)
                                    .seqNum(t.expectSeqNum)
                                    .build();
                            send(dataAck, pkt.srcNodeID);  // Send back to client's node
                            System.out.printf("[SRTServer] Sent DATAACK (ack=%d) to client %d%n",
                                    t.expectSeqNum, t.portClient);
                            break;

                        default:
                            System.out.printf("[SRTServer] Ignored segment type %s%n", seg.type);
                            break;
                    }
                }
            } catch (IOException e) {
                if (running) System.err.println("[SRTServer] Listener I/O error: " + e.getMessage());
            }
        }, "SRTServerListener");

        listener.setDaemon(true);
        listener.start();
    }


    /**
     * PROJECT 4: Send segment wrapped in packet through network layer.
     * The packet is sent back through the same router connection we received from.
     * @param seg The SRT segment to send
     * @param destNodeID The destination node ID (client node)
     */
    private void send(SRTSegment seg, int destNodeID) {
        try {
            // PROJECT 4: Wrap in packet for routing back to client
            Packet pkt = new Packet(myNodeId, destNodeID, seg);
            byte[] bytes = pkt.toBytes();

            synchronized (out) {
                out.writeInt(bytes.length);   // length prefix
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("[SRTServer] Send error: " + e.getMessage());
        }
    }


    private boolean valid(int id) {
        return id>=0 && id<table.length && table[id]!=null;
    }

    private TCB findbyServerPort(int p) {
        for (TCB t: table) if (t != null && t.portServer == p) return t;
        return null;
    }
}