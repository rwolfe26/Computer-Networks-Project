/**
 * Packet.java
 *
 * Network-layer packet for Project 4.
 * Wraps a transport-layer SRTSegment and routing metadata.
 */
import java.nio.ByteBuffer;
import java.util.Objects;

public final class Packet {
    public final int srcNodeID;
    public final int destNodeID;
    public final SRTSegment seg;

    public Packet(int srcNodeID, int destNodeID, SRTSegment seg) {
        this.srcNodeID = srcNodeID;
        this.destNodeID = destNodeID;
        this.seg = Objects.requireNonNull(seg, "seg");
    }

    public byte[] toBytes() {
        byte[] segBytes = seg.toBytes();
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES * 3 + segBytes.length);
        buf.putInt(srcNodeID);
        buf.putInt(destNodeID);
        buf.putInt(segBytes.length);
        buf.put(segBytes);
        return buf.array();
    }

    public static Packet fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < Integer.BYTES * 3) {
            throw new IllegalArgumentException("Packet buffer too short");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int src = buf.getInt();
        int dest = buf.getInt();
        int segLen = buf.getInt();

        if (segLen < 0 || bytes.length != Integer.BYTES * 3 + segLen) {
            throw new IllegalArgumentException("Packet length mismatch");
        }

        byte[] segBytes = new byte[segLen];
        buf.get(segBytes);

        SRTSegment seg = SRTSegment.fromBytes(segBytes);
        return new Packet(src, dest, seg);
    }

    @Override
    public String toString() {
        return "Packet{src=" + srcNodeID +
                ", dest=" + destNodeID +
                ", type=" + seg.type +
                ", seq=" + seg.seqNum +
                ", len=" + seg.length +
                "}";
    }
}

