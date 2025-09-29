import java.io.Serializable;

import java.io.Serializable;
import java.util.Date;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public String text;
    public String from;
    public String to;
    public Date created;

    public Message() {
        this.created = new Date();
    }

    public Message(String text, String from, String to) {
        this.text = text;
        this.from = from;
        this.created = new Date();
    }

    @Override
    public String toString() {
        return String.format("Message{from=%s, to=%s, text=%s, created=%s}", from, to, text, created);
    }
}
