package com.ragapp.dto;

public record DocumentInfo(
        String documentId,
        String filename,
        int chunks
) {}
