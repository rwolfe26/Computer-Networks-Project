import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * This is the server with two modes
 * The single client, that uses the string only with the Data Input and Output Stream
 * The multi client chat server uses the Object Input & Output Stream and the serializable Message.
 */
public class Server {
    private static final int DEFAULT_PORT = 59090;
    private static void main(String[] args) throws Exception{
        boolean simple = false;
        int port = DEFAULT_PORT;
        for (int i = 0; i < args.length; i++){
            if ("--simple".equals(args[i])) simple = true;
            else if ("--port".equals(args[i]) && i+1 < args.length){
                port = Integer.parseInt(args[++i]);
            }
        }
        if (simple){
            runSimple(port);
        } else {
            runChat(port);
        }
    }


// This is the mode that uses the string over TCP. Here is where a single client sends one UTF string, the server replies and then closes.
private static void runSimple(int port) throws Exception{
    System.out.println("[Server(simple)] Starting on port" + port);
    try (ServerSocket welcomSocket = new ServerSocket(port)) {
        Socket connectionSocket = welcomSocket.accept();
        System.out.println("[Server(simple)] Client connected from " + connectionSocket.getRemoteSocketAddress());

        //The DataOutputStream is created first and then the DataInputStream.
        DataOutputStream out = new DataOutputStream(connectionSocket.getOutputStream());
        DataInputStream in = new DataInputStream(connectionSocket.getInputStream());

        // This reads one line
        String clientLine = in.readUTF();
        System.out.println(("[Server(simple)] Received: " + clientLine));

        //The different responses with a set of patterns.
        String lower = clientLine.trim().toLowerCase(Locale.ROOT);
        String reply;
        if (lower.contains("hello")){
            reply = "I am good, how about you?";
        } else if (lower.contains("time")) {
            reply = "Server time is " + new Date();
        } else if (lower.contains("Chicken")) {
            reply = "Nuggets";
        } else {
            // These are the reponses from the ArrayList
            List<String> responses = new ArrayList<>();
            responses.add("Morning!");
            responses.add("Okay say More");
            responses.add("Hmm Interesting");
            responses.add("Cool");
            reply = responses.get(new Random().nextInt(responses.size()));
        }
        out.writeUTF(reply);
        out.flush();

        // The teardown
        in.close();
        out.close();
        connectionSocket.close();
        System.out.println("[Server(simple)] Connection is closed.");

    }
}

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
                //creates the ObjectOutputStream first, flush header, then ObjectInputStream
                this.out = new ObjectOutputStream(socket.getOutputStream());
                this.out.flush();
                this.in = new ObjectInputStream(socket.getInputStream());

                System.out.printf("[Server] Client #%d connected from %s%n", id, socket.getRemoteSocketAddress());

                // Sends a welcome with the given ID
                send(new Message("WELCOME: Your ID is " + id + ". Use `to` as another client's ID or `*` to broadcast.", "server", String.valueOf(id)));

                // Main loop
                while (!socket.isClosed()) {
                    Object obj = in.readObject();
                    if (!(obj instanceof Message)) {
                        // Ignores the unknowns
                        continue;
                    }
                    Message m = (Message) obj;
                    if (m.text == null) m.text = "";
                    String txt = m.text.trim();

                    // Handles simple commands
                    if (txt.startsWith("/quit")) {
                        send(new Message("Goodbye!", "server", String.valueOf(id)));
                        break;
                    }
                    if (txt.startsWith("/name ")) {
                        String newName = txt.substring(6).trim();
                        if (!newName.isEmpty()) {
                            String old = (name == null ? ("Client-"+id) : name);
                            name = newName;
                            parent.broadcast(new Message("* " + old + " is now known as " + name, "server", "*"), this);
                        }
                        continue;
                    }
                    if (name == null && txt.startsWith("/hello")) {
                        // client introduction: "/hello <name>"
                        String[] parts = txt.split("\\s+", 2);
                        if (parts.length == 2) {
                            name = parts[1].trim();
                        } else {
                            name = "Client-" + id;
                        }
                        parent.broadcast(new Message("* " + name + " joined", "server", "*"), this);
                        continue;
                    }

                    // Forwarding: to "*" means broadcast, else tries to  parse the ID
                    if (m.to != null && (m.to.equals("*") || m.to.equalsIgnoreCase("broadcast"))) {
                        String sender = (name == null ? ("Client-"+id) : name);
                        parent.broadcast(new Message("[" + sender + "] " + m.text, String.valueOf(id), "*"), null);
                    } else if (m.to != null) {
                        try {
                            int targetId = Integer.parseInt(m.to.trim());
                            String sender = (name == null ? ("Client-"+id) : name);
                            boolean ok = parent.forwardTo(targetId, new Message("[" + sender + " -> you] " + m.text, String.valueOf(id), String.valueOf(targetId)));
                            if (!ok) {
                                send(new Message("No such client id: " + targetId, "server", String.valueOf(id)));
                            }
                        } catch (NumberFormatException nfe) {
                            send(new Message("Invalid recipient. Use a numeric client ID or '*' for broadcast.", "server", String.valueOf(id)));
                        }
                    } else {
                        send(new Message("Please set Message.to to a client ID or '*'.", "server", String.valueOf(id)));
                    }
                }
            } catch (EOFException eof) {
                // client is now closed
            } catch (SocketException se) {
                // connection is now closed
            } catch (Exception e) {
                System.err.println("[Server] Error with client #" + id + ": " + e);
            } finally {
                close();
                parent.removeClient(id);
            }
        }
        
        // pushes one Message to this client
        void send(Message m) {
            try {
                out.writeObject(m);
                out.flush();
            } catch (IOException e) {
                // ignores
            }
        }

        // the cleanup for this client's resources
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

// launher for the multi client chat server.
private static void runChat(int port) throws Exception {
    Server s = new Server();
    s.runChatImpl(port);
}

// this accepts the loop for the chat server and then each client is handed to its own handler thread
private void runChatImpl(int port) throws Exception {
    System.out.println("[Server(chat)] Starting on port " + port);
    try (ServerSocket ss = new ServerSocket(port)) {
        while (true) {
            Socket sock = ss.accept();
            int id = nextId.getAndIncrement();
            ClientHandler ch = new ClientHandler(this, sock, id);
            clients.put(id, ch);
            clientList.add(ch);
            System.out.printf("[Server(chat)] Assigned Id %d to %s%n", id, sock.getRemoteSocketAddress());
            ch.start();
        }
    }
}

// this cleans when a client disconnects and the client is removed from the clients and clientlist.
void removeClient(int id){
    ClientHandler ch = clients.remove(id);
    if (ch != null) {
        clientList.remove(ch);
    }
}

// delivers a message to a single ID
boolean forwardTo(int id, Message m) {
    ClientHandler ch = clients.get(id);
    if (ch == null) return false;
    ch.send(m);
    return true;
}

// braoadcast routing which delivers to all of the connected clients.
void broadcast(Message m, ClientHandler exclude) {
    synchronized (clientList) {
        for (ClientHandler ch : clientList) {
            if (exclude != null && ch == exclude) continue;
            ch.send(m);
        }
    }
}


}
