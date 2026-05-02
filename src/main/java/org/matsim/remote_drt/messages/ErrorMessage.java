package org.matsim.remote_drt.messages;

public class ErrorMessage implements Message {
    public enum Type {
        Format, Sequence
    }

    public Type type;
    public String message;
}
