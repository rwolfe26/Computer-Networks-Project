import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server.java
 *
 * This server has three modes:
 * 1) --simple : Single client using Data{Input,Output}Stream
 * 2) --srt    : PROJECT 4 SRT demo with network layer routing
 * 3) (default): Multi-client chat using ObjectStreams and Message objects
 */
public class Server {
    private static final int DEFAULT_PORT = 59090;

    public static void main(String[] args) throws Exception{
        boolean simple = false;
        boolean srt = false;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++){
            if ("--simple".equals(args[i])) simple = true;
            else if("--srt".equals(args[i])) srt = true;
            else if ("--port".equals(args[i]) && i+1 < args.length){
                port = Integer.parseInt(args[++i]);
            }
        }

        if (srt){
            runSrtDemo(port);
        } else if (simple){
            runSimple(port);
        } else {
            runChat(port);
        }
    }


    /**
     * PROJECT 4: SRT Demo with Network Layer Routing
     *
     * Key differences from previous projects:
     * - Server NO LONGER listens for direct client connections
     * - Server listens on its network port for ROUTER connections
     * - Packets arrive via routing: Client(77) -> Router(521) -> Router(96) -> Server(382)
     * - startOverlay now loads network.dat and listens on server's network port
     */
    private static void runSrtDemo(int overlayPort) throws Exception {
        System.out.println("[SRT] PROJECT 4 Server demo with network layer routing");
        System.out.println("[SRT] Server will listen for connections FROM router, not directly from client");

        SRTServer srt = new SRTServer(382, 77);  // server node 382, client node 77

        // PROJECT 4: startOverlay now listens on server's network port for router connection
        // The overlayPort parameter is now ignored - uses network.dat topology
        if (srt.startOverlay(overlayPort) < 0) {
            throw new RuntimeException("Failed to accept router connection");
        }

        if (srt.init() < 0) {
            throw new RuntimeException("init failed");
        }

        // Create SRT socket on port 88
        int s1 = srt.createSock(88);
        if (s1 < 0) throw new RuntimeException("createSock(88) failed");

        System.out.println("[SRT] Listening on SRT port 88 for incoming connections...");
        System.out.println("[SRT] (Packets will arrive via: Client node 77 -> Router 521 -> Router 96 -> Server node 382)");

        if (srt.accept(s1) < 0) {
            System.out.println("[SRT] accept(88) timed out/failed");
        } else {
            System.out.println("[SRT] SRT connection established on port 88");
        }

        Thread.sleep(12_000);

        if (srt.close(s1) < 0) System.out.println("[SRT] close(88) not yet closed");
        srt.stopOverlay();
        System.out.println("[SRT] demo complete");
    }


    /* -------------------------------------------------------------
     * Mode 1: Simple one-shot server
     * ------------------------------------------------------------- */
    private static void runSimple(int port) throws Exception{
        System.out.println("[Server(simple)] Starting on port " + port);
        try (ServerSocket welcomeSocket = new ServerSocket(port)) {
            Socket connectionSocket = welcomeSocket.accept();
            System.out.println("[Server(simple)] Client connected from " +
                    connectionSocket.getRemoteSocketAddress());

            // DataOutputStream first, then DataInputStream
            DataOutputStream out = new DataOutputStream(connectionSocket.getOutputStream());
            DataInputStream in = new DataInputStream(connectionSocket.getInputStream());

            // Read one line
            String clientLine = in.readUTF();
            System.out.println("[Server(simple)] Received: " + clientLine);

            // Generate response
            String lower = clientLine.trim().toLowerCase(Locale.ROOT);
            String reply;
            if (lower.contains("hello")){
                reply = "I am good, how about you?";
            } else if (lower.contains("time")) {
                reply = "Server time is " + new Date();
            } else if (lower.contains("chicken")) {
                reply = "Nuggets";
            } else {
                List<String> responses = new ArrayList<>();
                responses.add("Morning!");
                responses.add("Okay say More");
                responses.add("Hmm Interesting");
                responses.add("Cool");
                reply = responses.get(new Random().nextInt(responses.size()));
            }

            out.writeUTF(reply);
            out.flush();

            // Teardown
            in.close();
            out.close();
            connectionSocket.close();
            System.out.println("[Server(simple)] Connection closed.");
        }
    }

    /* -------------------------------------------------------------
     * Mode 2: Multi-client chat server
     * ------------------------------------------------------------- */
    private static class ClientHandler extends Thread {
        private final Socket socket;
        private final int id;
        private ObjectInputStream in;
        private ObjectOutputStream out;
        private String name = null;
        private final Server parent;

        ClientHandler(Server parent, Socket socket, int id) {
            this.parent = parent;
            this.socket = socket;
            this.id = id;
        }

        @Override
        public void run() {
            try {
                // ObjectOutputStream first, flush header, then ObjectInputStream
                this.out = new ObjectOutputStream(socket.getOutputStream());
                this.out.flush();
                this.in = new ObjectInputStream(socket.getInputStream());

                System.out.printf("[Server] Client #%d connected from %s%n",
                        id, socket.getRemoteSocketAddress());

                // Send welcome
                send(new Message("WELCOME: Your ID is " + id +
                        ". Use client ID or '*' for broadcast.", "server", String.valueOf(id)));

                // Main loop
                while (!socket.isClosed()) {
                    Object obj = in.readObject();
                    if (!(obj instanceof Message)) continue;

                    Message m = (Message) obj;
                    if (m.text == null) m.text = "";
                    String txt = m.text.trim();

                    // Handle commands
                    if (txt.startsWith("/quit")) {
                        send(new Message("Goodbye!", "server", String.valueOf(id)));
                        break;
                    }

                    if (txt.startsWith("/name ")) {
                        String newName = txt.substring(6).trim();
                        if (!newName.isEmpty()) {
                            String old = (name == null ? ("Client-"+id) : name);
                            name = newName;
                            parent.broadcast(new Message("* " + old + " is now known as " + name,
                                    "server", "*"), this);
                        }
                        continue;
                    }

                    if (name == null && txt.startsWith("/hello")) {
                        String[] parts = txt.split("\\s+", 2);
                        if (parts.length == 2) {
                            name = parts[1].trim();
                        } else {
                            name = "Client-" + id;
                        }
                        parent.broadcast(new Message("* " + name + " joined", "server", "*"), this);
                        continue;
                    }

                    // Forward message
                    String sender = (name == null ? ("Client-"+id) : name);
                    if (m.to != null && (m.to.equals("*") || m.to.equalsIgnoreCase("broadcast"))) {
                        parent.broadcast(new Message("[" + sender + "] " + m.text,
                                String.valueOf(id), "*"), null);
                    } else if (m.to != null) {
                        try {
                            int targetId = Integer.parseInt(m.to.trim());
                            boolean ok = parent.forwardTo(targetId,
                                    new Message("[" + sender + " -> you] " + m.text,
                                            String.valueOf(id), String.valueOf(targetId)));
                            if (!ok) {
                                send(new Message("No such client id: " + targetId,
                                        "server", String.valueOf(id)));
                            }
                        } catch (NumberFormatException nfe) {
                            send(new Message("Invalid recipient. Use numeric ID or '*'.",
                                    "server", String.valueOf(id)));
                        }
                    } else {
                        send(new Message("Please set Message.to to a client ID or '*'.",
                                "server", String.valueOf(id)));
                    }
                }
            } catch (EOFException eof) {
                // client closed
            } catch (SocketException se) {
                // connection closed
            } catch (Exception e) {
                System.err.println("[Server] Error with client #" + id + ": " + e);
            } finally {
                close();
                parent.removeClient(id);
            }
        }

        void send(Message m) {
            try {
                out.writeObject(m);
                out.flush();
            } catch (IOException e) {
                // ignore
            }
        }

        void close() {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if(out != null) out.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
            System.out.printf("[Server] Client #%d disconnected%n", id);
        }
    }

    private final ConcurrentHashMap<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final List<ClientHandler> clientList = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger nextId = new AtomicInteger(1);

    private static void runChat(int port) throws Exception {
        Server s = new Server();
        s.runChatImpl(port);
    }

    private void runChatImpl(int port) throws Exception {
        System.out.println("[Server(chat)] Starting on port " + port);
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket sock = ss.accept();
                int id = nextId.getAndIncrement();
                ClientHandler ch = new ClientHandler(this, sock, id);
                clients.put(id, ch);
                clientList.add(ch);
                System.out.printf("[Server(chat)] Assigned ID %d to %s%n",
                        id, sock.getRemoteSocketAddress());
                ch.start();
            }
        }
    }

    void removeClient(int id){
        ClientHandler ch = clients.remove(id);
        if (ch != null) {
            clientList.remove(ch);
        }
    }

    boolean forwardTo(int id, Message m) {
        ClientHandler ch = clients.get(id);
        if (ch == null) return false;
        ch.send(m);
        return true;
    }

    void broadcast(Message m, ClientHandler exclude) {
        synchronized (clientList) {
            for (ClientHandler ch : clientList) {
                if (exclude != null && ch == exclude) continue;
                ch.send(m);
            }
        }
    }
}