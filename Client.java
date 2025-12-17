import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client.java
 *
 * Works with three modes:
 *   1) --simple  : string-only round trip using Data{Input,Output}Stream
 *   2) --srt     : PROJECT 4 SRT demo with network layer routing
 *   3) (default) : multi-client chat using Object streams and Message objects
 *
 * Flags:
 *   --host <nameOrIp>   (default: localhost)
 *   --port <num>        (default: 59090)
 *   --simple            (run the simple one-shot string client)
 *   --srt               (run PROJECT 4 SRT demo with routing through routers)
 *   --name <display>    (chat mode only; sends "/hello <display>" on connect)
 *   --msg  "<text>"     (simple mode only; message to send once)
 */
public class Client {

    public static void main(String[] args) {
        String host = "localhost";
        int port = 59090;
        boolean simple = false;
        boolean srt = false;
        String name = null;
        String simpleMsg = null;

        // Basic arg parsing
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--simple":
                    simple = true;
                    break;
                case "--srt":
                    srt = true;
                    break;
                case "--name":
                    if (i + 1 < args.length) name = args[++i];
                    break;
                case "--msg":
                    if (i + 1 < args.length) simpleMsg = args[++i];
                    break;
                default:
                    // ignore unknowns for now
            }
        }
        if (srt){
            runSrtDemo(host, port);
            return;
        }
        if (simple) {
            runSimple(host, port, simpleMsg);
        } else {
            runChat(host, port, name);
        }
    }


    /**
     * PROJECT 4: SRT Demo with Network Layer Routing
     *
     * Key differences from previous projects:
     * - Client NO LONGER connects directly to server
     * - Client connects to its NEXT-HOP ROUTER (node 521 in network.dat)
     * - Packets are routed through the network: Client(77) -> Router(521) -> Router(96) -> Server(382)
     * - startOverlay now loads network.dat and determines routing
     */
    private static void runSrtDemo(String host, int port) {
        System.out.println("[client] PROJECT 4 SRT demo with network layer routing");
        System.out.println("[client] Client will connect to ROUTER, not directly to server");

        SRTClient srtc = new SRTClient();

        // PROJECT 4: startOverlay now connects to next-hop router based on network.dat
        // The 'port' parameter is ignored - client uses network.dat topology
        if (srtc.startOverlay(host, port) < 0) {
            System.err.println("[client] Failed to connect to next-hop router");
            return;
        }

        if (srtc.initSRTClient() < 0) {
            System.err.println("[client] init failed");
            srtc.stopOverlay();
            return;
        }

        // Connection from client SRT port 87 to server SRT port 88
        // Packets will be routed through network layer
        int sock1 = srtc.createSockSRTClient(87);
        if (sock1 < 0) {
            System.err.println("createSock failed");
            srtc.stopOverlay();
            return;
        }

        System.out.println("[client] Establishing SRT connection: port 87 -> port 88");
        System.out.println("[client] (Packets routed: Client node 77 -> Router 521 -> Router 96 -> Server node 382)");

        if (srtc.connectSRTClient(sock1, 88) < 0){
            System.err.println("connect failed");
            srtc.stopOverlay();
            return;
        }

        System.out.println("[client] SRT connection established (87->88)");

        byte[] msg = "Hello from SRT with network layer routing!".getBytes();
        srtc.sendSRTClient(sock1, msg);
        System.out.println("[client] Sent DATA message through network layer");

        try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}

        System.out.println("[client] Initiating disconnect (87->88)");
        if (srtc.disconnectSRTClient(sock1) < 0) System.err.println("disconnect returned -1");
        if (srtc.closeSRTClient(sock1) < 0) System.err.println("close returned -1");

        srtc.stopOverlay();
        System.out.println("[client] SRT demo complete.");
    }

    /* -------------------------------------------------------------
     * Mode 1: Simple one-shot client (Section 2.7 style)
     * ------------------------------------------------------------- */
    private static void runSimple(String host, int port, String msgArg) {
        System.out.println("[client] simple mode → " + host + ":" + port);
        try (Socket sock = new Socket(host, port)) {
            // IMPORTANT: Output stream FIRST, then Input stream
            try (DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                 DataInputStream in   = new DataInputStream(sock.getInputStream())) {

                String msg = msgArg;
                if (msg == null || msg.isEmpty()) {
                    System.out.print("Enter a message to send: ");
                    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                    msg = br.readLine();
                    if (msg == null || msg.isEmpty()) msg = "hello, how are you";
                }

                out.writeUTF(msg);
                out.flush();

                String reply = in.readUTF();
                System.out.println("[server] " + reply);
            }
        } catch (IOException e) {
            System.err.println("[client] I/O error: " + e.getMessage());
        }
    }

    /* -------------------------------------------------------------
     * Mode 2: Multi-client chat client using Message objects
     * ------------------------------------------------------------- */
    private static void runChat(String host, int port, String displayName) {
        System.out.println("[client] chat mode → " + host + ":" + port);
        final AtomicBoolean running = new AtomicBoolean(true);

        try (Socket sock = new Socket(host, port)) {
            // IMPORTANT: ObjectOutputStream FIRST, then flush header, then ObjectInputStream
            ObjectOutputStream oos = new ObjectOutputStream(sock.getOutputStream());
            oos.flush();
            ObjectInputStream ois = new ObjectInputStream(sock.getInputStream());

            // If a display name was provided, introduce yourself
            if (displayName != null && !displayName.isEmpty()) {
                Message hello = new Message("/hello " + displayName, displayName, "*");
                oos.writeObject(hello);
                oos.flush();
            }

            // Thread: read from server and print to console
            Thread reader = new Thread(() -> {
                try {
                    while (running.get()) {
                        Object obj = ois.readObject();
                        if (!(obj instanceof Message)) {
                            System.out.println("[server] (unknown object received)");
                            continue;
                        }
                        Message m = (Message) obj;

                        // Build a readable line
                        String toPart = (m.to == null || m.to.equals("*") || m.to.equalsIgnoreCase("broadcast"))
                                ? "all"
                                : m.to;
                        String fromPart = (m.from == null ? "server" : m.from);
                        String timePart = (m.created == null ? "" : "[" + m.created + "] ");

                        System.out.println(timePart + fromPart + " → " + toPart + ": " + m.text);
                    }
                } catch (EOFException eof) {
                    System.out.println("[client] server closed the connection.");
                } catch (IOException | ClassNotFoundException e) {
                    if (running.get()) {
                        System.err.println("[client] reader error: " + e.getMessage());
                    }
                } finally {
                    running.set(false);
                    try { ois.close(); } catch (Exception ignore) {}
                    try { oos.close(); } catch (Exception ignore) {}
                    try { sock.close(); } catch (Exception ignore) {}
                }
            }, "server-reader");

            // Thread: read from console and send to server
            Thread writer = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                    while (running.get()) {
                        String line = br.readLine();
                        if (line == null) { // EOF from console
                            break;
                        }
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        // Support commands:
                        //   /quit                → tells server to close this client
                        //   /name NewName        → rename
                        //   /hello MyName        → set initial name (if not already set)
                        //   @<id> message        → DM to client id (e.g., @3 hey)
                        //   anything else        → broadcast
                        String to = "*"; // default broadcast

                        if (line.equalsIgnoreCase("/quit")) {
                            Message m = new Message("/quit", displayName, "*");
                            oos.writeObject(m);
                            oos.flush();
                            running.set(false);
                            break;
                        }

                        if (line.startsWith("@")) {
                            // Parse "@<number> rest of message"
                            int space = line.indexOf(' ');
                            if (space > 1) {
                                String maybeId = line.substring(1, space).trim();
                                String rest = line.substring(space + 1).trim();
                                if (!rest.isEmpty()) {
                                    to = maybeId; // server routes numeric ids
                                    line = rest;
                                }
                            }
                        }

                        // Send the message object
                        Message m = new Message(line, displayName, to);
                        oos.writeObject(m);
                        oos.flush();
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[client] writer error: " + e.getMessage());
                    }
                } finally {
                    running.set(false);
                    try { sock.close(); } catch (Exception ignore) {}
                }
            }, "console-writer");

            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();

            // Block main thread until writer finishes
            try {
                writer.join();
            } catch (InterruptedException ignored) { }

            // Stop reader and close
            running.set(false);
            try { sock.close(); } catch (Exception ignore) {}
            System.out.println("[client] disconnected.");

        } catch (IOException e) {
            System.err.println("[client] could not connect: " + e.getMessage());
        }
    }
}