package com.ragapp.dto;

import java.time.LocalDateTime;

public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}
