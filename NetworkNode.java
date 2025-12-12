import java.util.HashMap;
import java.util.Map;

/**
 * NetworkNode.java
 *
 * Stores a nodeId + port and a "next hop" routing table:
 *   destinationNodeId -> nextHopNodeId
 *
 * This is the key structure the SNP network layer will query to forward packets.
 */
public class NetworkNode {
    public final int nodeId;
    public final int port;

    // destination -> next hop
    public final Map<Integer, Integer> nextHop = new HashMap<>();

    public NetworkNode(int nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
    }

    /**
     * Returns the next hop nodeId for a given destination, or -1 if unknown/unreachable.
     */
    public int nextHopFor(int destNodeId) {
        Integer hop = nextHop.get(destNodeId);
        return (hop == null) ? -1 : hop;
    }

    @Override
    public String toString() {
        return "NetworkNode{id=" + nodeId + ", port=" + port + "}";
    }
}
