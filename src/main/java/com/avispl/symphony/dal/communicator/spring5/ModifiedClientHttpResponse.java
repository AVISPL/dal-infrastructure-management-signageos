/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.spring5;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Spring 5 compatible version of ClientHttpResponse
 * */
public class ModifiedClientHttpResponse implements ClientHttpResponse {
    private ClientHttpResponse response;
    private int statusCode;
    private String modifiedResponse;

    public ModifiedClientHttpResponse(ClientHttpResponse response, String modifiedResponse, int statusCode) {
        this.response = response;
        this.statusCode = statusCode;
        this.modifiedResponse = modifiedResponse;
    }

    @Override
    public org.springframework.http.HttpStatus getStatusCode() throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return org.springframework.http.HttpStatus.OK;
        } else if (statusCode >= 400 && statusCode < 500) {
            return org.springframework.http.HttpStatus.UNAUTHORIZED;
        } else if (statusCode >= 500 && statusCode < 600) {
            return org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return org.springframework.http.HttpStatus.OK;
    }

    @Override
    public int getRawStatusCode() throws IOException {
        return response.getRawStatusCode();
    }
    @Override
    public String getStatusText() throws IOException {
        return response.getStatusText();
    }
    @Override
    public void close() {
        response.close();
    }
    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(modifiedResponse.getBytes(StandardCharsets.UTF_8));
    }
    @Override
    public HttpHeaders getHeaders() {
        return response.getHeaders();
    }
}
