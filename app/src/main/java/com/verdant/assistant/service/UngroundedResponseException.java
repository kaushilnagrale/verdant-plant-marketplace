package com.verdant.assistant.service;

/** A cultural/care claim with no real citation, or one citing a chunk that was never actually retrieved - fabricating an id to satisfy a schema is exactly the failure mode this guards against. */
public class UngroundedResponseException extends RuntimeException {
    public UngroundedResponseException(String message) {
        super(message);
    }
}
