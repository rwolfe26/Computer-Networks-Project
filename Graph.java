import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Graph.java
 *
 * Loads router nodes + weighted links from network.dat, then supports
 * shortest-path routing (Dijkstra) and next-hop table construction.
 *
 * network.dat format:
 *   nodeID port
 *   nodeID1 nodeID2 cost      (undirected)
 *
 * Lines may contain leading/trailing whitespace.
 * Blank lines and lines starting with '#' are ignored.
 */
public class Graph {

    // nodeId -> NetworkNode (contains port + nextHop table)
    private final Map<Integer, NetworkNode> nodes = new HashMap<>();

    // adjacency list: u -> (v -> cost)
    private final Map<Integer, Map<Integer, Integer>> adj = new HashMap<>();

    public Map<Integer, NetworkNode> getNodesView() {
        return Collections.unmodifiableMap(nodes);
    }

    public NetworkNode getNode(int nodeId) {
        return nodes.get(nodeId);
    }

    public int getPort(int nodeId) {
        NetworkNode n = nodes.get(nodeId);
        if (n == null) throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
        return n.port;
    }

    public Map<Integer, Integer> neighbors(int nodeId) {
        return Collections.unmodifiableMap(adj.getOrDefault(nodeId, Collections.emptyMap()));
    }

    public void addNode(int nodeId, int port) {
        nodes.putIfAbsent(nodeId, new NetworkNode(nodeId, port));
        adj.putIfAbsent(nodeId, new HashMap<>());
    }

    public void addUndirectedEdge(int u, int v, int cost) {
        if (cost < 0) throw new IllegalArgumentException("Edge cost must be >= 0");
        if (!nodes.containsKey(u) || !nodes.containsKey(v)) {
            throw new IllegalStateException("Edge references unknown node(s): " + u + " " + v);
        }
        adj.get(u).put(v, cost);
        adj.get(v).put(u, cost);
    }

    /**
     * Reads network.dat and loads nodes + edge weights.
     */
    public void readNetworkDat(String path) throws IOException {
        // clear any prior state
        nodes.clear();
        adj.clear();

        List<int[]> pendingEdges = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;

                String[] tok = line.split("\\s+");
                try {
                    if (tok.length == 2) {
                        int nodeId = Integer.parseInt(tok[0]);
                        int port = Integer.parseInt(tok[1]);
                        addNode(nodeId, port);
                    } else if (tok.length == 3) {
                        int u = Integer.parseInt(tok[0]);
                        int v = Integer.parseInt(tok[1]);
                        int cost = Integer.parseInt(tok[2]);
                        pendingEdges.add(new int[]{u, v, cost});
                    } else {
                        throw new IOException("Bad line (expected 2 or 3 tokens) at " + lineNo + ": " + line);
                    }
                } catch (NumberFormatException nfe) {
                    throw new IOException("Bad number at line " + lineNo + ": " + line, nfe);
                }
            }
        }

        // add edges after nodes exist (allows any ordering in file)
        for (int[] e : pendingEdges) {
            addUndirectedEdge(e[0], e[1], e[2]);
        }

        if (nodes.isEmpty()) {
            throw new IOException("No nodes found in network.dat (" + path + ")");
        }
    }

    /* ------------------------- Dijkstra + next hop ------------------------- */

    public static final class DijkstraResult {
        public final Map<Integer, Integer> dist;  // nodeId -> cost
        public final Map<Integer, Integer> prev;  // nodeId -> predecessor on shortest path tree

        private DijkstraResult(Map<Integer, Integer> dist, Map<Integer, Integer> prev) {
            this.dist = dist;
            this.prev = prev;
        }
    }

    /**
     * Dijkstra from startId over this weighted graph.
     * Returns dist + prev maps.
     */
    public DijkstraResult dijkstra(int startId) {
        requireNode(startId);

        Map<Integer, Integer> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();

        for (int id : nodes.keySet()) {
            dist.put(id, Integer.MAX_VALUE);
            prev.put(id, null);
        }
        dist.put(startId, 0);

        // (nodeId, dist)
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
        pq.add(new int[]{startId, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int u = cur[0];
            int du = cur[1];

            if (du != dist.get(u)) continue; // stale entry

            for (Map.Entry<Integer, Integer> edge : adj.getOrDefault(u, Collections.emptyMap()).entrySet()) {
                int v = edge.getKey();
                int w = edge.getValue();
                if (dist.get(u) == Integer.MAX_VALUE) continue;

                // safe add (avoid overflow)
                long altL = (long) dist.get(u) + (long) w;
                int alt = altL > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) altL;

                if (alt < dist.get(v)) {
                    dist.put(v, alt);
                    prev.put(v, u);
                    pq.add(new int[]{v, alt});
                }
            }
        }

        return new DijkstraResult(dist, prev);
    }

    /**
     * Builds and stores the nextHop table for the given start node.
     *
     * For each destination D, nextHop[D] is the first neighbor after start
     * along the cheapest path (per Dijkstra).
     */
    public void buildNextHopTable(int startId) {
        requireNode(startId);

        NetworkNode start = nodes.get(startId);
        DijkstraResult res = dijkstra(startId);

        start.nextHop.clear();

        for (int destId : nodes.keySet()) {
            if (destId == startId) continue;
            if (res.prev.get(destId) == null) continue; // unreachable

            int hop = computeNextHop(startId, destId, res.prev);
            if (hop != -1) start.nextHop.put(destId, hop);
        }
    }

    /**
     * Builds nextHop tables for every node in the graph.
     */
    public void buildAllNextHopTables() {
        for (int nodeId : nodes.keySet()) {
            buildNextHopTable(nodeId);
        }
    }

    /**
     * Returns the full shortest path from startId to endId (inclusive).
     * Empty list if unreachable.
     */
    public List<Integer> shortestPath(int startId, int endId) {
        requireNode(startId);
        requireNode(endId);

        DijkstraResult res = dijkstra(startId);
        if (startId != endId && res.prev.get(endId) == null) return Collections.emptyList();

        LinkedList<Integer> path = new LinkedList<>();
        Integer cur = endId;
        path.addFirst(cur);

        while (cur != null && !cur.equals(startId)) {
            cur = res.prev.get(cur);
            if (cur == null) return Collections.emptyList();
            path.addFirst(cur);
        }
        return path;
    }

    /**
     * Given a predecessor map from Dijkstra, compute the next hop from start to dest.
     * Returns -1 if unreachable or malformed prev chain.
     */
    public static int computeNextHop(int startId, int destId, Map<Integer, Integer> prev) {
        if (startId == destId) return -1;

        // Walk backwards from dest until we find the node whose predecessor is start.
        Integer hop = destId;
        Integer p = prev.get(hop);

        while (p != null && !p.equals(startId)) {
            hop = p;
            p = prev.get(hop);
        }
        if (p == null) return -1;
        return hop;
    }

    private void requireNode(int nodeId) {
        if (!nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
        }
    }

    /* ------------------------- quick sanity test ------------------------- */

    public static void main(String[] args) throws Exception {
        String path = (args.length > 0) ? args[0] : "network.dat";
        Graph g = new Graph();
        g.readNetworkDat(path);
        g.buildAllNextHopTables();

        System.out.println("Loaded nodes: " + g.getNodesView().keySet());
        for (int id : g.getNodesView().keySet()) {
            System.out.println("Node " + id + " (port " + g.getPort(id) + ") nextHop: " + g.getNode(id).nextHop);
        }
    }
}
