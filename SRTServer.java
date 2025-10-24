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


    //overlays 
    private ServerSocket overlayListen;
    private Socket overlaySock;
    private DataInputStream in;
    private DataOutputStream out;

    //Listener
    private Thread listener;
    private volatile boolean running;

    public static class TCB {
        int portServer, portClient;
        volatile int state = S_CLOSED;
    }

    private final TCB[] table = new TCB[32];

    //lifecycle of the overlay start and stop methods

    public int startOverlay(int port) {
        try {
            overlayListen = new ServerSocket(port);
            overlaySock = overlayListen.accept();
            out = new DataOutputStream(new BufferedOutputStream(overlaySock.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(overlaySock.getInputStream()));
            return 1;
        } catch (Exception e) { e.printStackTrace(); return -1; }
    }

    public int stopOverlay(){
        running = false;
        try { if (listener != null) listener.join(200); } catch (InterruptedException ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (overlaySock != null) overlaySock.close(); } catch (Exception ignored) {}
        try { if (overlayListen != null) overlayListen.close(); } catch (Exception ignored) {}
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


    // listener section
    private void startListener() {
        running = true;
        listener = new Thread(() -> {
            try {
                while (running) {
                    int frameLen;
                    try {
                        frameLen = in.readInt();          // length prefix
                    } catch (EOFException eof) {
                        System.out.println("[SRTServer] Client disconnected (EOF).");
                        break;
                    }
                    if (frameLen <= 0) continue;
    
                    byte[] buf = new byte[frameLen];
                    in.readFully(buf);
                    SRTSegment seg = SRTSegment.fromBytes(buf);
    
                    System.out.printf(
                        "[SRTServer] Received %s (src=%d → dest=%d, len=%d)%n",
                        seg.type, seg.srcPort, seg.destPort, seg.length
                    );
    
                    // Route by server’s SRT port
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
                                send(synack);
                                System.out.printf("[SRTServer] Sent SYNACK to client %d%n", t.portClient);
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
                                send(finack);
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
                            // Optional show DATAACK:
                            SRTSegment dataAck = new SRTSegment.Builder()
                                .type(SRTSegment.Type.DATAACK)
                                .srcPort(t.portServer)
                                .destPort(t.portClient)
                                .build();
                            send(dataAck);
                            System.out.printf("[SRTServer] Sent DATAACK to client %d%n", t.portClient);
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
    
    

    private void send(SRTSegment seg) {
        try {
            byte[] bytes = seg.toBytes();
            out.writeInt(bytes.length);   // for the length prefix
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {}
    }
    




    private boolean valid(int id) {
        return id>=0 && id<table.length && table[id]!=null;
    }

    private TCB findbyServerPort(int p) {
        for (TCB t: table) if (t != null && t.portServer == p) return t;
        return null;
    }











}