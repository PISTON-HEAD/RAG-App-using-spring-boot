package com.ragapp.auth;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for JwtService.
 * Validates token creation, extraction, and validation using a test secret key.
 */
class JwtServiceTest {

    private JwtService jwtService;

    // 256-bit Base64-encoded HMAC key (test key — never use in production)
    private static final String TEST_SECRET =
            "dGVzdFNlY3JldEtleUZvckp3dEF1dGhUZXN0aW5nMTIzNDU2";

    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", 3_600_000L); // 1 hour

        userDetails = User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of())
                .build();
    }

    @Test
    @DisplayName("generateToken: produces a non-null, non-empty JWT string")
    void generateToken_returnsNonEmptyString() {
        String token = jwtService.generateToken(userDetails);

        assertThat(token).isNotNull().isNotEmpty();
        // JWT has exactly 3 dot-separated parts
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("extractUsername: returns the correct subject from a valid token")
    void extractUsername_returnsCorrectUsername() {
        String token = jwtService.generateToken(userDetails);

        String extracted = jwtService.extractUsername(token);

        assertThat(extracted).isEqualTo("testuser");
    }

    @Test
    @DisplayName("isTokenValid: returns true for a fresh token with the matching user")
    void isTokenValid_returnsTrueForMatchingUser() {
        String token = jwtService.generateToken(userDetails);

        boolean valid = jwtService.isTokenValid(token, userDetails);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("isTokenValid: returns false when username does not match token subject")
    void isTokenValid_returnsFalseForWrongUser() {
        String token = jwtService.generateToken(userDetails);

        UserDetails otherUser = User.builder()
                .username("anotheruser")
                .password("password")
                .authorities(List.of())
                .build();

        boolean valid = jwtService.isTokenValid(token, otherUser);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("isTokenValid: returns false for expired token")
    void isTokenValid_returnsFalseForExpiredToken() {
        // Set expiration to 1ms so it's immediately expired
        ReflectionTestUtils.setField(jwtService, "expirationMs", 1L);
        String token = jwtService.generateToken(userDetails);

        // Give it a brief moment to expire
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        boolean valid = jwtService.isTokenValid(token, userDetails);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("extractUsername: throws exception for a tampered/invalid token")
    void extractUsername_throwsForInvalidToken() {
        assertThatThrownBy(() -> jwtService.extractUsername("not.a.valid.jwt"))
                .isInstanceOf(Exception.class);
    }
}
