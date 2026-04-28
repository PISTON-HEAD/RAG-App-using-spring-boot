package com.ragapp.exception;

import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for GlobalExceptionHandler.
 * Verifies error responses for common failure modes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.ai.google.genai.api-key=test-key-does-not-call-gemini"
})
class GlobalExceptionHandlerTest {

    // Mock AI beans so the context loads without touching OpenAI
    @MockBean EmbeddingModel embeddingModel;
    @MockBean ChatModel chatModel;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Bad credentials → JSON error body with 'error' field and 401 status")
    void badCredentials_returns401WithErrorBody() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WRONG\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Invalid username or password"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Validation error → 400 with descriptive 'error' field")
    void validationError_returns400WithMessage() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Gemini 429 error â†’ 429 with provider details")
    void geminiQuotaError_returns429WithProviderDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<java.util.Map<String, Object>> response = handler.handleGeminiClientError(
                new ClientException(429, "RESOURCE_EXHAUSTED", "quota exceeded")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsEntry("providerCode", 429);
        assertThat(response.getBody()).containsEntry("providerStatus", "RESOURCE_EXHAUSTED");
        assertThat(response.getBody().get("error").toString()).contains("Gemini quota/rate limit reached");
    }
}
