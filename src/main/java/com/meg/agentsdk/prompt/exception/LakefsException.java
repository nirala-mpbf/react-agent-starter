package com.meg.agentsdk.prompt.exception;

import org.springframework.http.HttpStatus;

public class LakefsException extends RuntimeException{
    private final HttpStatus status;

    public LakefsException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public LakefsException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

