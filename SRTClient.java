import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
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
//        SRT CLIENT
// -------------------------------
public class SRTClient {

    private Socket overlaySocket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

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


    // -------------------------------
    // INITIALIZATION
    // -------------------------------
    public int initSRTClient() {
        try {
            tcbTable = new TCBClient[MAX_TCB_ENTRIES];
            for (int i = 0; i < MAX_TCB_ENTRIES; i++) tcbTable[i] = null;

            ListenThread listener = new ListenThread(inputStream, tcbTable);
            listener.start();

            System.out.println("SRTClient initialized.");
            return 1;
        } catch (Exception e) {
            System.err.println("Could not initialize SRTClient: " + e.getMessage());
            return -1;
        }
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
            new Thread(new SendThread(tcb)).start();
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
    // HELPER: send segment
    // -------------------------------
    int sendSegment(SRTSegment seg) {
        try {
            byte[] b = seg.toBytes();
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
    // OVERLAY TCP SETUP
    // -------------------------------
    public int startOverlay(String host, int port) {
        try {
            overlaySocket = new Socket(InetAddress.getByName(host), port);
            outputStream = new DataOutputStream(overlaySocket.getOutputStream());
            inputStream = new DataInputStream(overlaySocket.getInputStream());
            return 1;
        } catch (Exception e) {
            return -1;
        }
    }

    public int stopOverlay() {
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (overlaySocket != null) overlaySocket.close();
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

                SRTSegment seg = SRTSegment.fromBytes(buf);
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
        synchronized (tcb.sendBuffer) {
            tcb.sendBuffer.removeIf(
                    node -> node.segment.seqNum + node.segment.length <= ackNum
            );
            tcb.sendBufUnsent = Math.max(0, tcb.sendBufUnsent - 1);
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

    public SendThread(TCBClient t) { this.tcb = t; }

    @Override
    public void run() {
        while (tcb.clientState == SRTCState.CONNECTED && !tcb.sendBuffer.isEmpty()) {

            synchronized (tcb.sendBuffer) {
                while (tcb.sendBufUnsent < Math.min(tcb.sendBuffer.size(), 10)) {

                    SendBufferNode node = tcb.sendBuffer.get(tcb.sendBufUnsent);
                    node.sentTime = System.currentTimeMillis();

                    // Send the DATA segment
                    // (we need a reference to SRTClient's sendSegment, so store it externally)
                    SRTClientHolder.client.sendSegment(node.segment);

                    tcb.sendBufUnsent++;
                }
            }

            try { Thread.sleep(50); } catch (Exception ignored) {}

            synchronized (tcb.sendBuffer) {
                if (!tcb.sendBuffer.isEmpty()) {
                    SendBufferNode head = tcb.sendBuffer.getFirst();
                    long now = System.currentTimeMillis();
                    if (now - head.sentTime > 200) {
                        // timeout → resend all
                        for (int i = 0; i < tcb.sendBufUnsent; i++) {
                            SendBufferNode node = tcb.sendBuffer.get(i);
                            node.sentTime = System.currentTimeMillis();
                            SRTClientHolder.client.sendSegment(node.segment);
                        }
                    }
                }
            }
        }

        tcb.workerRunning = false;
    }
}



// ===============================
// GLOBAL CLIENT HANDLE
// (needed so SendThread can call sendSegment)
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
    SRTCState clientState;
    Timer timer;

    // Assignment 3 fields:
    LinkedList<SendBufferNode> sendBuffer;
    int nextSeqNum;
    int sendBufUnsent;
    boolean workerRunning;
}
