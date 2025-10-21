import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Header fields are fixed-width ints; payload is 'length'
 * Flags are encoded with the 'type' field (SYN=0, SYNACK=1, FIN=2, FINACK=3, DATA=4, DATAACK=5)
 * Spec fields: src_port, dest_port, seq_num, length, type, rcv_win, checksum, data
 */
public final class SRTSegment {
    // Constants
    public static final int MAX_SEG_LEN = 1024;          // max cap
    public static final int HEADER_BYTES = Integer.BYTES * 7; // 7 int fields

    /** Segment types ("flags") */
    public enum Type {
        SYN(0), SYNACK(1), FIN(2), FINACK(3), DATA(4), DATAACK(5);
        public final int code;
        Type(int code) { this.code = code; }
        public static Type fromCode(int code) {
            for (Type t : values()) if (t.code == code) return t;
            throw new IllegalArgumentException("Invalid Type code: " + code);
        }
    } 

    // HEADER 
    public final int srcPort;
    public final int destPort;
    public final int seqNum;
    public final int length;
    public final Type type;
    public final int rcvWin;
    public final int checksum;

    // PAYLOAD
    public final byte[] data;

    private SRTSegment(Builder b) {
        this.srcPort  = b.srcPort;
        this.destPort = b.destPort;
        this.seqNum   = b.seqNum;
        this.type     = Objects.requireNonNull(b.type, "type");
        this.data     = b.data == null ? new byte[0] : b.data.clone();
        if (this.data.length > MAX_SEG_LEN)
            throw new IllegalArgumentException("Data length exceeds max segment length");
        this.length   = this.data.length;
        this.rcvWin   = b.rcvWin;
        this.checksum = b.checksum;
    }

    // Builder
    public static final class Builder {
        private int srcPort, destPort, seqNum, rcvWin, checksum;
        private Type type;
        private byte[] data;

        public Builder type(Type t)        { this.type = t; return this; }
        public Builder srcPort(int p)      { this.srcPort = p; return this; }
        public Builder destPort(int p)     { this.destPort = p; return this; }
        public Builder seqNum(int s)       { this.seqNum = s; return this; }
        public Builder rcvWin(int w)       { this.rcvWin = w; return this; }
        public Builder checksum(int c)     { this.checksum = c; return this; }
        public Builder data(byte[] d)      { this.data = (d == null ? null : d.clone()); return this; }
        public Builder data(String s)      { return data(s == null ? null : s.getBytes(StandardCharsets.UTF_8)); }
        public SRTSegment build()          { return new SRTSegment(this); }
    }

    // Serialization to bytes
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + length);
        buffer.putInt(srcPort);
        buffer.putInt(destPort);
        buffer.putInt(seqNum);
        buffer.putInt(length);
        buffer.putInt(type.code);
        buffer.putInt(rcvWin);
        buffer.putInt(checksum);
        if (length > 0) buffer.put(data, 0, length);
        return buffer.array();
    }

    /** Parse a received buffer into an SRTSegment. Expects a full header, then 'length' payload bytes */
    public static SRTSegment fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES)
            throw new IllegalArgumentException("Byte array too short to contain SRTSegment header");

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int srcPort  = buffer.getInt();
        int destPort = buffer.getInt();
        int seqNum   = buffer.getInt();
        int length   = buffer.getInt();
        int typeCode = buffer.getInt();
        int rcvWin   = buffer.getInt();
        int checksum = buffer.getInt();

        if (length < 0 || length > MAX_SEG_LEN)
            throw new IllegalArgumentException("Invalid segment length: " + length);
        if (bytes.length != HEADER_BYTES + length)
            throw new IllegalArgumentException("Buffer size mismatch: expected " + (HEADER_BYTES + length)
                                               + ", got " + bytes.length);

        byte[] payload = (length == 0) ? new byte[0]
                                       : Arrays.copyOfRange(bytes, HEADER_BYTES, HEADER_BYTES + length);

        return new SRTSegment.Builder()
                .srcPort(srcPort)
                .destPort(destPort)
                .seqNum(seqNum)
                .type(Type.fromCode(typeCode))
                .rcvWin(rcvWin)
                .checksum(checksum)
                .data(payload)
                .build();
    }

    @Override public String toString() {
        return "SRTSegment{type=" + type + ", seq=" + seqNum + ", len=" + length + "}";
    }
}
