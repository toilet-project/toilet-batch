package com.example.toiletbatch.publicdata;

public class PublicDataApiException extends RuntimeException {

    public PublicDataApiException(String message) {
        super(message);
    }

    public PublicDataApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
