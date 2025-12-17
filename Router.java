import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Router.java
 *
 * Network layer router that:
 * 1. Reads network.dat to build topology and next-hop tables
 * 2. Listens for incoming packets on its assigned port
 * 3. Forwards packets to the next hop based on destination nodeID
 *
 * Usage: java Router <nodeID>
 */
public class Router {

    private final int nodeID;
    private final int port;
    private final Graph graph;
    private final NetworkNode myNode;

    // Active connections to other routers/nodes (nodeID -> Socket)
    private final ConcurrentHashMap<Integer, Socket> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, DataOutputStream> outStreams = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public Router(int nodeID, String networkFile) throws IOException {
        this.nodeID = nodeID;

        // Load network topology
        this.graph = new Graph();
        graph.readNetworkDat(networkFile);

        // Build next-hop tables for all nodes
        graph.buildAllNextHopTables();

        // Get my node info
        this.myNode = graph.getNode(nodeID);
        if (myNode == null) {
            throw new IllegalArgumentException("Node " + nodeID + " not found in network.dat");
        }
        this.port = myNode.port;

        System.out.printf("[Router %d] Initialized on port %d%n", nodeID, port);
        System.out.printf("[Router %d] Next-hop table: %s%n", nodeID, myNode.nextHop);
    }

    /**
     * Start the router: listen for incoming packets and forward them
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.printf("[Router %d] Listening on port %d%n", nodeID, port);

        // Accept connections in a separate thread
        Thread acceptThread = new Thread(this::acceptConnections, "Router-" + nodeID + "-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.printf("[Router %d] Ready to forward packets%n", nodeID);
    }

    /**
     * Accept incoming connections from other routers/clients/servers
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.printf("[Router %d] Accepted connection from %s%n",
                        nodeID, clientSocket.getRemoteSocketAddress());

                // Handle this connection in a separate thread
                Thread handler = new Thread(() -> handleConnection(clientSocket),
                        "Router-" + nodeID + "-Handler-" + clientSocket.getPort());
                handler.setDaemon(true);
                handler.start();

            } catch (IOException e) {
                if (running) {
                    System.err.printf("[Router %d] Accept error: %s%n", nodeID, e.getMessage());
                }
            }
        }
    }

    /**
     * Handle an incoming connection: read packets and forward them
     */
    private void handleConnection(Socket socket) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            while (running && !socket.isClosed()) {
                try {
                    // Read packet length prefix
                    int packetLen = in.readInt();
                    if (packetLen <= 0 || packetLen > 100000) {
                        System.err.printf("[Router %d] Invalid packet length: %d%n", nodeID, packetLen);
                        break;
                    }

                    // Read packet data
                    byte[] packetData = new byte[packetLen];
                    in.readFully(packetData);

                    // Deserialize packet
                    Packet packet = Packet.fromBytes(packetData);

                    System.out.printf("[Router %d] Received packet: src=%d, dest=%d, type=%s%n",
                            nodeID, packet.srcNodeID, packet.destNodeID, packet.seg.type);

                    // Forward the packet
                    forwardPacket(packet);

                } catch (EOFException eof) {
                    System.out.printf("[Router %d] Connection closed by peer%n", nodeID);
                    break;
                } catch (Exception e) {
                    System.err.printf("[Router %d] Error processing packet: %s%n", nodeID, e.getMessage());
                    e.printStackTrace();
                    break;
                }
            }

        } catch (IOException e) {
            System.err.printf("[Router %d] Connection handler error: %s%n", nodeID, e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Forward a packet to the next hop based on destination
     */
    private void forwardPacket(Packet packet) {
        int destNodeID = packet.destNodeID;

        // Check if this packet is for me (shouldn't happen in a pure router)
        if (destNodeID == nodeID) {
            System.out.printf("[Router %d] Packet reached destination (me)%n", nodeID);
            return;
        }

        // Look up next hop
        int nextHop = myNode.nextHopFor(destNodeID);
        if (nextHop == -1) {
            System.err.printf("[Router %d] No route to destination %d, dropping packet%n",
                    nodeID, destNodeID);
            return;
        }

        System.out.printf("[Router %d] Forwarding packet (dest=%d) to next hop %d%n",
                nodeID, destNodeID, nextHop);

        try {
            // Get or create connection to next hop
            DataOutputStream out = getOutputStream(nextHop);

            // Send packet with length prefix
            byte[] packetData = packet.toBytes();
            out.writeInt(packetData.length);
            out.write(packetData);
            out.flush();

            System.out.printf("[Router %d] Successfully forwarded to node %d%n", nodeID, nextHop);

        } catch (IOException e) {
            System.err.printf("[Router %d] Failed to forward to node %d: %s%n",
                    nodeID, nextHop, e.getMessage());
            // Remove failed connection
            closeConnection(nextHop);
        }
    }

    /**
     * Get or create output stream to a neighbor node.
     * PROJECT 4 FIX: Also starts a listener thread for bidirectional communication.
     */
    private synchronized DataOutputStream getOutputStream(int neighborID) throws IOException {
        // Check if we already have a connection
        DataOutputStream out = outStreams.get(neighborID);
        if (out != null) {
            return out;
        }

        // Create new connection
        int neighborPort = graph.getPort(neighborID);
        System.out.printf("[Router %d] Creating connection to node %d on port %d%n",
                nodeID, neighborID, neighborPort);

        Socket socket = new Socket("localhost", neighborPort);
        connections.put(neighborID, socket);

        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        outStreams.put(neighborID, out);

        // PROJECT 4 FIX: Start a listener thread for this outgoing connection
        // to handle bidirectional communication (responses coming back)
        Thread listenerThread = new Thread(() -> handleConnection(socket),
                "Router-" + nodeID + "-Listener-" + neighborID);
        listenerThread.setDaemon(true);
        listenerThread.start();

        System.out.printf("[Router %d] Started bidirectional listener for node %d%n",
                nodeID, neighborID);

        return out;
    }

    /**
     * Close connection to a specific node
     */
    private synchronized void closeConnection(int neighborID) {
        DataOutputStream out = outStreams.remove(neighborID);
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {}
        }

        Socket socket = connections.remove(neighborID);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        System.out.printf("[Router %d] Closed connection to node %d%n", nodeID, neighborID);
    }

    /**
     * Shutdown the router
     */
    public void shutdown() {
        running = false;

        // Close all connections
        for (int neighborID : new ArrayList<>(connections.keySet())) {
            closeConnection(neighborID);
        }

        // Close server socket
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        System.out.printf("[Router %d] Shutdown complete%n", nodeID);
    }

    /**
     * Main method: start a router with given node ID
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Router <nodeID> [network.dat]");
            System.err.println("Example: java Router 521");
            System.exit(1);
        }

        try {
            int nodeID = Integer.parseInt(args[0]);
            String networkFile = (args.length > 1) ? args[1] : "network.dat";

            Router router = new Router(nodeID, networkFile);
            router.start();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.printf("%n[Router %d] Shutting down...%n", nodeID);
                router.shutdown();
            }));

            // Keep router running
            System.out.printf("[Router %d] Press Ctrl+C to stop%n", nodeID);
            Thread.currentThread().join();

        } catch (NumberFormatException e) {
            System.err.println("Error: nodeID must be an integer");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Router error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}