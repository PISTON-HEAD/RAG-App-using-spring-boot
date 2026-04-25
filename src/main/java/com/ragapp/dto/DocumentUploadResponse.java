package com.ragapp.dto;

public record DocumentUploadResponse(
        String documentId,
        String filename,
        int totalChunks,
        String message
) {}
