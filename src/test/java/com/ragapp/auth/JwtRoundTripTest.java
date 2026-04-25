package com.ragapp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragapp.dto.LoginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end JWT round-trip test.
 *
 * 1. Login → extract JWT
 * 2. Use JWT to call protected endpoints
 * 3. Verify a tampered token is rejected
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key-does-not-call-openai"
})
class JwtRoundTripTest {

    // Mock AI beans so the context loads without touching OpenAI
    @MockBean EmbeddingModel embeddingModel;
    @MockBean ChatModel chatModel;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest(username, password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("token").asText();
    }

    @Test
    @DisplayName("Full round-trip: login → use token → access protected GET /documents")
    void loginThenAccessProtectedEndpoint_succeeds() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // valid JWT structure

        // Use the token to hit a protected endpoint
        mockMvc.perform(get("/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Tampered token → 401/403 on protected endpoint")
    void tamperedToken_isRejected() throws Exception {
        String validToken = loginAndGetToken("user", "user123");
        // Corrupt the signature part (last segment)
        String[] parts = validToken.split("\\.");
        String tampered = parts[0] + "." + parts[1] + ".invalidsignature";

        mockMvc.perform(get("/documents")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    @DisplayName("No Authorization header → 401/403 on protected endpoint")
    void noToken_isRejected() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    @DisplayName("Malformed Bearer value → 401/403 on protected endpoint")
    void malformedBearer_isRejected() throws Exception {
        mockMvc.perform(get("/documents")
                        .header("Authorization", "Bearer this.is.garbage"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
