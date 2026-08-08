package com.nexusagent.identity.api;

public final class InvalidCredentialsException
        extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid tenant code, username or password");
    }
}