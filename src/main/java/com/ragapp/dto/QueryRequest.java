package com.ragapp.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "Question is required") String question
) {}
