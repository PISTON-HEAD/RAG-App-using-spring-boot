package com.ragapp.dto;

import java.util.List;

public record QueryResponse(
        String answer,
        String question,
        String documentId,
        List<String> relevantChunks
) {}
