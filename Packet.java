/**
 * Packet.java
 *
 * Network-layer packet for Project 4.
 * Wraps a transport-layer SRTSegment and routing metadata.
 */
public final class Packet {
    public final int srcNodeID;
    public final int destNodeID;
    public final SRTSegment seg;

    public Packet(int srcNodeID, int destNodeID, SRTSegment seg) {
        this.srcNodeID = srcNodeID;
        this.destNodeID = destNodeID;
        this.seg = seg;
    }

    @Override
    public String toString() {
        return "Packet{src=" + srcNodeID +
               ", dest=" + destNodeID +
               ", type=" + (seg == null ? "null" : seg.type) +
               ", seq=" + (seg == null ? "-" : seg.seqNum) +
               "}";
    }
}
