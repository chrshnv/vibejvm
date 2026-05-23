package dev.vibejvm.error;

public class LinkageException extends RuntimeException {
    public LinkageException(String msg) { super(msg); }
    public LinkageException(String msg, Throwable cause) { super(msg, cause); }
}
