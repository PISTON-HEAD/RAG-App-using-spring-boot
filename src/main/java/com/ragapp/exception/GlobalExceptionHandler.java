package com.ragapp.exception;

import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import com.google.genai.errors.ClientException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "Invalid username or password",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", ex.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(Map.of(
                "error", message,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "File size exceeds the maximum allowed size (10MB)",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                "error", "Wrong Content-Type. Use Body → form-data in Postman, set key='file' type=File.",
                "received", ex.getContentType() != null ? ex.getContentType().toString() : "none",
                "required", "multipart/form-data",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Multipart request error. Ensure you are sending a file via form-data with key 'file'.",
                "detail", ex.getMessage() != null ? ex.getMessage() : "(no detail)",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Missing required parameter: '" + ex.getParameterName() + "'. In Postman, set form-data key='" + ex.getParameterName() + "' type=File.",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<Map<String, Object>> handleGeminiClientError(ClientException ex) {
        HttpStatus status = ex.code() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        String message = ex.code() == 429
                ? "Gemini quota/rate limit reached for the configured Google AI project. Verify the API key, project billing, and AI Studio quota tier."
                : "Gemini rejected the request: " + ex.message();

        return ResponseEntity.status(status).body(Map.of(
                "error", message,
                "providerStatus", ex.status(),
                "providerCode", ex.code(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // Spring AI wraps ClientException inside RuntimeException("Failed to generate content").
        // Walk the cause chain to detect it and return a proper 429 instead of a generic 500.
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof ClientException clientEx) {
                return handleGeminiClientError(clientEx);
            }
            cause = cause.getCause();
        }
        // Log the full stack trace so it appears in the console
        log.error("Unhandled exception [{}]: {}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An unexpected error occurred. Please try again later.",
                "exceptionType", ex.getClass().getName(),
                "exceptionMessage", ex.getMessage() != null ? ex.getMessage() : "(no message)",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
