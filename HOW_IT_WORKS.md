# Spring Boot RAG Application — Complete Technical Guide

> **Goal of this document:** Teach you everything about this project at the code level — for someone who is new to RAG, Spring Boot, LLMs, and vector embeddings.  
> From an HTTP request arriving at the server, through authentication, document parsing, embedding, vector storage, similarity search, and LLM answer generation — every single step explained.

---

## Table of Contents

1. [What is RAG?](#1-what-is-rag)
2. [Architecture Overview — Gemini + ONNX](#2-architecture-overview--gemini--onnx)
3. [Project Overview & Package Structure](#3-project-overview--package-structure)
4. [How Spring Boot Starts Up](#4-how-spring-boot-starts-up)
5. [Authentication Layer — JWT Deep Dive](#5-authentication-layer--jwt-deep-dive)
   - 5.1 [What is JWT?](#51-what-is-jwt)
   - 5.2 [SecurityConfig & the Filter Chain](#52-securityconfig--the-filter-chain)
   - 5.3 [JwtService — Token Creation & Validation](#53-jwtservice--token-creation--validation)
   - 5.4 [JwtAuthenticationFilter — Request Interception](#54-jwtauthenticationfilter--request-interception)
   - 5.5 [AuthController — The Login Endpoint](#55-authcontroller--the-login-endpoint)
6. [What is Multipart? How Files Travel Over HTTP](#6-what-is-multipart-how-files-travel-over-http)
7. [Document Ingestion Pipeline — Step by Step](#7-document-ingestion-pipeline--step-by-step)
   - 7.1 [Apache Tika — Parsing Any File Type](#71-apache-tika--parsing-any-file-type)
   - 7.2 [Text Chunking — Why & How](#72-text-chunking--why--how)
   - 7.3 [What is a Vector / Embedding?](#73-what-is-a-vector--embedding)
   - 7.4 [How Text Becomes a Vector — ONNX Deep Dive](#74-how-text-becomes-a-vector--onnx-deep-dive)
   - 7.5 [OnnxEmbeddingModel — The Custom Class Explained](#75-onnxembeddingmodel--the-custom-class-explained)
   - 7.6 [SimpleVectorStore — How Vectors Are Stored](#76-simplevectorstore--how-vectors-are-stored)
   - 7.7 [Metadata Tagging — The documentId Filter](#77-metadata-tagging--the-documentid-filter)
8. [RAG Query Pipeline — The Full Flow](#8-rag-query-pipeline--the-full-flow)
   - 8.1 [Embedding the Question](#81-embedding-the-question)
   - 8.2 [Cosine Similarity Search](#82-cosine-similarity-search)
   - 8.3 [Building the Augmented Prompt](#83-building-the-augmented-prompt)
   - 8.4 [Calling Google Gemini](#84-calling-google-gemini)
9. [Per-Document vs Cross-Document Query](#9-per-document-vs-cross-document-query)
10. [All Exposed API Endpoints](#10-all-exposed-api-endpoints)
11. [Configuration — application.properties Explained](#11-configuration--applicationproperties-explained)
12. [How Every Class Fits Together (Dependency Map)](#12-how-every-class-fits-together-dependency-map)
13. [End-to-End Request Traces](#13-end-to-end-request-traces)

---

## 1. What is RAG?

**RAG = Retrieval-Augmented Generation**

Without RAG, if you ask Gemini "What does clause 12 of my employment contract say?" it has no idea — it has never seen your contract and may hallucinate a plausible-sounding but wrong answer.

RAG solves this by giving the LLM relevant context _at query time_:

```
Traditional LLM:
  User question ──────────────────────────► LLM ──► Answer (may hallucinate)

RAG:
  User question ──► Search your documents ──► Find relevant passages
                                                      │
                                                      ▼
                             User question + retrieved passages ──► LLM ──► Grounded answer
```

The LLM is not guessing. It reads your document's relevant parts and summarizes what it finds.

**The three pillars of RAG:**

| Pillar | What it does | Technology in this app |
|--------|-------------|------------------------|
| **Retrieval** | Find relevant text from your documents | ONNX all-MiniLM-L6-v2 + SimpleVectorStore |
| **Augmentation** | Combine retrieved text with the question | `QueryService.buildResponse()` |
| **Generation** | Produce a fluent, grounded answer | Google Gemini 2.0 Flash |

---

## 2. Architecture Overview — Gemini + ONNX

This application uses **two separate AI models** for two completely different jobs:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          EMBEDDING (Local, Free)                            │
│                                                                             │
│  Text ──► [HuggingFace Tokenizer] ──► tokens ──► [ONNX Runtime]            │
│                                                        │                   │
│               all-MiniLM-L6-v2 model (86 MB)           │                   │
│               runs entirely on your machine            ▼                   │
│                                              float[384] vector              │
│                                                                             │
│  No API key needed. No internet call at inference time. Always free.        │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                        GENERATION (Cloud, API Key)                          │
│                                                                             │
│  Prompt ──► [Spring AI ChatClient] ──► HTTPS ──► Google Gemini API          │
│                                                        │                   │
│             gemini-2.0-flash model                     ▼                   │
│             (Google's cloud servers)           Natural language answer      │
│                                                                             │
│  Requires: GOOGLE_GENAI_API_KEY. Billed per token (but has a free tier).   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Why two different AI services?**

- **Embeddings** (turning text into vectors for semantic search) are a solved problem. Small, open-source models like `all-MiniLM-L6-v2` do this extremely well, run locally, and are completely free. There is no reason to pay an external API for this.
- **Generation** (synthesizing a coherent, human-readable answer from context) benefits from a large, powerful model. Google Gemini 2.0 Flash is a state-of-the-art model with excellent reasoning capabilities.

---

## 3. Project Overview & Package Structure

```
spring-rag-app/
├── pom.xml                              # Maven dependencies
└── src/main/java/com/ragapp/
    │
    ├── SpringRagApplication.java        # @SpringBootApplication entry point
    │
    ├── config/
    │   ├── AiConfig.java               # Wires up EmbeddingModel + SimpleVectorStore
    │   ├── OnnxEmbeddingModel.java     # Custom ONNX embedding model (no DJL)
    │   ├── SecurityConfig.java          # HTTP security rules + filter chain
    │   └── UserConfig.java             # Hard-coded users, password encoder
    │
    ├── auth/
    │   ├── AuthController.java          # POST /auth/login
    │   ├── JwtService.java              # JWT create / validate
    │   └── JwtAuthenticationFilter.java # Intercepts every request, validates JWT
    │
    ├── document/
    │   ├── DocumentController.java      # POST /documents/upload, GET /documents
    │   └── DocumentService.java         # Parse → Chunk → Embed → Store
    │
    ├── query/
    │   ├── QueryController.java         # POST /documents/{id}/query
    │   ├── GlobalQueryController.java   # POST /query (cross-document)
    │   └── QueryService.java            # Similarity search → Prompt → Gemini → Answer
    │
    ├── dto/
    │   ├── LoginRequest.java
    │   ├── LoginResponse.java
    │   ├── DocumentUploadResponse.java
    │   ├── DocumentInfo.java
    │   ├── QueryRequest.java
    │   └── QueryResponse.java
    │
    └── exception/
        └── GlobalExceptionHandler.java  # Catches all errors, returns clean JSON
```

**Dependencies (from pom.xml) and why each one is there:**

| Dependency | Why We Need It |
|---|---|
| `spring-boot-starter-web` | HTTP server (Tomcat), REST controllers, JSON serialization |
| `spring-boot-starter-security` | Security filter chain, `@EnableWebSecurity`, `AuthenticationManager` |
| `spring-ai-starter-model-google-genai` | Auto-configures `ChatModel` for Google Gemini via REST API |
| `spring-ai-starter-model-transformers` | Provides `ResourceCacheService`, `HuggingFaceTokenizer`, ONNX infra classes |
| `spring-ai-vector-store` | `SimpleVectorStore` — in-memory vector store |
| `spring-ai-tika-document-reader` | Apache Tika — extracts text from PDF, DOCX, TXT, HTML, etc. |
| `jjwt-api` + `jjwt-impl` + `jjwt-jackson` | JWT token creation and parsing |
| `spring-boot-starter-validation` | `@NotBlank` and `@Valid` on request DTOs |
| `onnxruntime` (transitive) | Microsoft ONNX Runtime — actually runs the embedding model in Java |
| `tokenizers` (transitive, DJL) | HuggingFace Rust tokenizer — converts text to token IDs |

---

## 4. How Spring Boot Starts Up

```java
// SpringRagApplication.java
@SpringBootApplication(exclude = {TransformersEmbeddingModelAutoConfiguration.class})
public class SpringRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}
```

**Why is `TransformersEmbeddingModelAutoConfiguration` excluded?**

The `spring-ai-starter-model-transformers` dependency would normally auto-configure a `TransformersEmbeddingModel` bean. That bean's default mean-pooling implementation uses DJL (Deep Java Library), which tries to download PyTorch native binaries from the internet on first use. In corporate network environments with SSL certificate inspection this download fails. By excluding the auto-configuration, we prevent this from happening and instead use our own `OnnxEmbeddingModel` class (see [Section 7.5](#75-onnxembeddingmodel--the-custom-class-explained)) that does mean pooling entirely in pure Java with no native downloads.

**Boot sequence:**

```
main() called
  └─ SpringApplication.run()
       ├─ Creates ApplicationContext
       ├─ Scans for @Component / @Service / @Controller classes
       ├─ Reads application.properties
       ├─ Runs AutoConfiguration:
       │    ├─ Sees spring-ai-starter-model-google-genai → creates ChatModel (Gemini) bean
       │    └─ Skips TransformersEmbeddingModelAutoConfiguration (excluded)
       ├─ Runs AiConfig.java:
       │    ├─ Creates OnnxEmbeddingModel bean
       │    │    ├─ ResourceCacheService checks temp dir for cached model files
       │    │    ├─ Loads tokenizer.json → HuggingFaceTokenizer
       │    │    └─ Loads model.onnx (86 MB) → OrtSession (ONNX Runtime session)
       │    └─ Creates SimpleVectorStore(embeddingModel) bean
       ├─ Creates UserDetailsService with admin/user (UserConfig.java)
       ├─ Creates JwtService, JwtAuthenticationFilter
       ├─ Builds SecurityFilterChain (SecurityConfig.java)
       └─ Starts embedded Tomcat on port 8082
```

---

## 5. Authentication Layer — JWT Deep Dive

### 5.1 What is JWT?

**JWT = JSON Web Token**

It is a compact, self-contained token that looks like this:

```
eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZG1pbiIsInJvbGVzIjpbXSwiaWF0IjoxNzc3MDk2Mzg2LCJleHAiOjE3NzcxMDAwMDN9.abc123...
```

Split by dots, it has **3 parts**:

```
HEADER.PAYLOAD.SIGNATURE

Part 1 — Header (Base64 decoded):
{
  "alg": "HS384"     ← signing algorithm (HMAC-SHA384)
}

Part 2 — Payload (Base64 decoded):
{
  "sub": "admin",                   ← username (subject)
  "roles": [{"authority":"ROLE_ADMIN"}],
  "iat": 1777096386,                ← issued at (Unix timestamp)
  "exp": 1777100003                 ← expires at (iat + 1 hour)
}

Part 3 — Signature:
  HMAC-SHA384(base64(header) + "." + base64(payload), secretKey)
```

**Why is this secure?**

The signature is computed using a **secret key** that only the server knows. If anyone tampers with the payload (e.g., changes `"sub":"user"` to `"sub":"admin"`), the signature no longer matches and the server rejects it. No session storage needed on the server side — the token itself contains all the information.

### 5.2 SecurityConfig & the Filter Chain

```java
// SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, AuthenticationProvider authProvider) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // CSRF protection is for browser sessions with cookies.
            // We use stateless JWT so CSRF is not applicable.

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()   // /auth/login needs no token
                .anyRequest().authenticated()              // everything else needs JWT
            )

            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                // STATELESS = never create a HttpSession.
                // Every request MUST carry its own JWT.
            )

            .authenticationProvider(authProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

**The Filter Chain is a pipeline.** Every HTTP request passes through filters in order:

```
Incoming HTTP Request
       │
       ▼
[DisableEncodeUrlFilter]
[SecurityContextHolderFilter]
[HeaderWriterFilter]
[LogoutFilter]
[JwtAuthenticationFilter]   ◄── Our custom filter: reads JWT, populates SecurityContext
[UsernamePasswordAuthenticationFilter]
[ExceptionTranslationFilter]
[AuthorizationFilter]        ◄── Checks if the request is authenticated; rejects if not
       │
       ▼
  Your Controller
```

### 5.3 JwtService — Token Creation & Validation

```java
// JwtService.java
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;   // Hex-encoded 256-bit key from application.properties

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;  // 3600000 = 1 hour

    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
            .subject(userDetails.getUsername())           // "admin" → "sub" claim
            .claims(Map.of("roles", userDetails.getAuthorities()))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey())                    // HMAC-SHA384 with secret key
            .compact();                                   // produces "eyJhbGci..."
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (ExpiredJwtException e) {
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())   // verifies signature using same secret key
            .build()
            .parseSignedClaims(token)      // throws if signature mismatch or expired
            .getPayload();
    }
}
```

### 5.4 JwtAuthenticationFilter — Request Interception

```java
// JwtAuthenticationFilter.java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // OncePerRequestFilter: runs exactly once per HTTP request.

    @Override
    protected void doFilterInternal(request, response, filterChain) {

        // Step 1: Read the Authorization header
        String authHeader = request.getHeader("Authorization");
        // Expected: "Bearer eyJhbGci..."

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);  // no token → pass through
            return;
        }

        // Step 2: Extract the raw token
        String jwt = authHeader.substring(7);  // remove "Bearer " prefix

        try {
            // Step 3: Decode token → get username from "sub" claim
            String username = jwtService.extractUsername(jwt);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Step 4: Load full user details from InMemoryUserDetailsManager
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // Step 5: Validate: username matches + not expired
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // Step 6: Create authenticated token and put it in SecurityContext
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                        );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    // The AuthorizationFilter will now see this user as authenticated.
                }
            }
        } catch (Exception ignored) {
            // Tampered/malformed/expired token → SecurityContext stays empty → 403
        }

        filterChain.doFilter(request, response);
    }
}
```

### 5.5 AuthController — The Login Endpoint

```java
// AuthController.java
@PostMapping("/auth/login")
public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {

    // @Valid triggers Jakarta validation: @NotBlank on username and password.
    // If blank → MethodArgumentNotValidException → 400 Bad Request

    // Authenticate username/password using DaoAuthenticationProvider:
    //   1. Load user from InMemoryUserDetailsManager
    //   2. BCrypt.check(inputPassword, storedHash)
    //   3. Mismatch → BadCredentialsException → 401 Unauthorized
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.username(), request.password())
    );

    // Credentials are valid — generate a JWT
    UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
    String token = jwtService.generateToken(userDetails);

    return ResponseEntity.ok(new LoginResponse(token, request.username()));
    // {"token": "eyJhbGci...", "username": "admin"}
}
```

**Default users (in-memory, no database):**

| Username | Password | Role |
|----------|----------|------|
| `admin` | `admin123` | `ROLE_ADMIN` |
| `user` | `user123` | `ROLE_USER` |

---

## 6. What is Multipart? How Files Travel Over HTTP

When a client sends a file to a server, it cannot put binary bytes in a JSON body. It uses **multipart/form-data**:

```http
POST /documents/upload HTTP/1.1
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxk
Authorization: Bearer eyJhbGci...

------WebKitFormBoundary7MA4YWxk
Content-Disposition: form-data; name="file"; filename="report.pdf"
Content-Type: application/pdf

(binary PDF bytes here)
------WebKitFormBoundary7MA4YWxk--
```

Spring Boot automatically parses this with `@RequestParam("file") MultipartFile file`.  
File size limits are configured in `application.properties`:

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

If exceeded → `MaxUploadSizeExceededException` → `GlobalExceptionHandler` → 413 response.

---

## 7. Document Ingestion Pipeline — Step by Step

When `POST /documents/upload` is called, `DocumentService.ingestDocument()` runs through 5 steps:

```
MultipartFile (binary bytes)
      │
Step 1: PARSE  ─── Apache Tika ──────────────────► Plain text string
      │
Step 2: CHUNK  ─── TokenTextSplitter ────────────► List of ~800-token chunks
      │
Step 3: TAG    ─── Add metadata ─────────────────► {documentId: "uuid"} on each chunk
      │
Step 4: EMBED  ─── OnnxEmbeddingModel ───────────► Each chunk → float[384] vector (LOCAL)
      │
Step 5: STORE  ─── SimpleVectorStore.add() ──────► Vectors stored in memory HashMap
      │
      └── Return: {documentId, filename, totalChunks, "indexed successfully"}
```

### 7.1 Apache Tika — Parsing Any File Type

```java
// DocumentService.java — Step 1
TikaDocumentReader reader = new TikaDocumentReader(
    new InputStreamResource(file.getInputStream())
);
List<Document> rawDocuments = reader.get();
```

Tika detects the file type and extracts text:

| File Type | What Tika does |
|-----------|----------------|
| `.pdf` | Uses PDFBox to extract text from each page |
| `.docx` | Unzips Office Open XML, reads `word/document.xml` |
| `.txt` | Reads as-is |
| `.html` | Parses HTML tags, extracts visible text |
| `.xlsx` | Reads cell values |
| `.pptx` | Reads slide text |

The result is a `List<Document>` — Spring AI's wrapper around extracted text.

### 7.2 Text Chunking — Why & How

**Why can't we embed the whole document?**

1. **Token limits**: The ONNX model has a maximum input length (~512 tokens). A 10-page document is far larger.
2. **Precision**: One vector per whole document is an "average" of everything. A specific detail gets diluted. Small, focused chunks give precise retrieval.
3. **Context window**: When we send retrieved text to Gemini, we only want the relevant parts — not the entire document.

```java
// DocumentService.java — Step 2
TokenTextSplitter splitter = new TokenTextSplitter(
    chunkSize,    // 800 — target tokens per chunk
    chunkOverlap, // 100 — overlap tokens between adjacent chunks
    5,            // minimum chunk size (discard tiny fragments)
    10000,        // maximum chunk size ceiling
    true,         // keep separator characters
    List.of('.', '?', '!', ';')  // preferred split points (sentence boundaries)
);
List<Document> chunks = splitter.apply(rawDocuments);
```

**What is a "token"?** Roughly 4 characters. "Hello world" ≈ 2 tokens. Tokenization is language-model specific.

**Visual example with overlap:**

```
Original text:
"The contract is effective from January 1, 2025.
 Payment is due within 30 days. Late fees apply.
 Termination requires 60 days notice."

chunkSize=10 words, overlap=3 words (simplified):

Chunk 1: "The contract is effective from January 1, 2025. Payment is"
Chunk 2: "2025. Payment is due within 30 days. Late fees apply."
           ↑──── 3-word overlap ────↑
Chunk 3: "Late fees apply. Termination requires 60 days notice."
```

Overlap ensures that if a complete thought is split at a chunk boundary, it still appears fully in at least one chunk.

### 7.3 What is a Vector / Embedding?

A **vector** (or **embedding**) is a fixed-length list of floating-point numbers:

```
"The cat sat on the mat"      → [0.23, -0.11, 0.84, 0.07, -0.52, ...]   (384 numbers)
"A feline rested on a rug"    → [0.24, -0.10, 0.82, 0.06, -0.51, ...]   ← very similar!
"The stock market crashed"    → [0.91,  0.53, -0.20, 0.77,  0.34, ...]  ← very different
```

The key insight: **text with similar meaning → vectors that are geometrically close in 384-dimensional space.** This is the entire foundation of semantic search.

You don't need to find the exact same words. "What are adverse effects?" and "side effects include..." will produce similar vectors even though the words differ.

### 7.4 How Text Becomes a Vector — ONNX Deep Dive

The all-MiniLM-L6-v2 model is a **Transformer** model — the same architecture as BERT and GPT. It has learned through training on massive amounts of text that "certain positions in 384-dimensional space" correspond to "certain types of meaning".

**The full pipeline from text to vector:**

```
Input text: "Payment is due within 30 days."

Step A — Tokenization (HuggingFaceTokenizer):
  "Payment is due within 30 days."
      ↓
  Word-piece tokenization:
  ["[CLS]", "Payment", "is", "due", "within", "30", "days", ".", "[SEP]"]
      ↓
  Token IDs (integers):   [101, 7909, 2003, 2349, 2306, 2382, 2420, 1012, 102]
  Attention mask:         [1,   1,    1,   1,    1,    1,   1,    1,   1   ]
  Token type IDs:         [0,   0,    0,   0,    0,    0,   0,    0,   0   ]

Step B — Transformer Inference (ONNX Runtime):
  Input tensors:
    input_ids:      shape [1, 9]  (batch=1, sequence_length=9)
    attention_mask: shape [1, 9]
    token_type_ids: shape [1, 9]
      ↓
  6 Transformer layers process the tokens:
    Each layer: multi-head self-attention → feed-forward → layer norm
    Tokens "attend" to each other — each token's representation is influenced
    by the context of surrounding tokens
      ↓
  Output: last_hidden_state — shape [1, 9, 384]
    For each of the 9 tokens, a 384-dimensional context-aware vector

Step C — Mean Pooling (pure Java in OnnxEmbeddingModel):
  For each token position s (0 to 8):
    If attention_mask[s] == 1: include this token in the average
    If attention_mask[s] == 0: padding token, exclude

  pooled[d] = sum(tokenEmbeddings[s][d] * mask[s]) / sum(mask[s])
              for each dimension d (0 to 383)

  Result: float[384]  — one vector representing the entire sentence meaning
```

**Why mean pooling?**

The Transformer outputs one vector per input token. We need one vector per sentence. Mean pooling averages all non-padding token vectors (weighted by attention mask). This produces a vector that captures the overall semantic content of the sentence, not just any single word.

**Why [CLS] and [SEP] tokens?**

- `[CLS]` (ID: 101) = "Classification" token — added at the start of every input. In BERT-style models, its output vector sometimes represents the whole sentence. For mean pooling it is included in the average.
- `[SEP]` (ID: 102) = "Separator" token — marks the end of an input segment.

### 7.5 OnnxEmbeddingModel — The Custom Class Explained

`OnnxEmbeddingModel.java` is a custom Spring component that replaces the default `TransformersEmbeddingModel` from Spring AI. Here is the full explanation of why it exists and how it works.

**Why we wrote a custom class instead of using Spring AI's default:**

Spring AI's `TransformersEmbeddingModel` does tokenization and mean pooling via DJL (Deep Java Library). DJL's mean-pooling code downloads PyTorch native binaries (a ~200 MB download) on first use. In a corporate network environment where SSL certificate inspection blocks connections to `download.pytorch.org`, this download fails with an SSL handshake error, and the application crashes.

Our `OnnxEmbeddingModel`:
- Uses **ONNX Runtime** directly (already on the classpath via `onnxruntime` JAR) for model inference
- Uses **HuggingFace tokenizer** (via DJL's `tokenizers` module — a pre-compiled Rust library that does NOT need to download anything) for tokenization
- Implements **mean pooling in pure Java** — no native PyTorch, no internet download

**The model loading (`afterPropertiesSet`):**

```java
private static final String TOKENIZER_URI =
    "https://raw.githubusercontent.com/spring-projects/spring-ai/main"
    + "/models/spring-ai-transformers/src/main/.../tokenizer.json";

private static final String MODEL_URI =
    "https://media.githubusercontent.com/media/spring-projects/spring-ai"
    + "/.../model.onnx";

@Override
public void afterPropertiesSet() throws Exception {
    // ResourceCacheService caches files in:
    //   ${java.io.tmpdir}/spring-ai-onnx-generative/{uuid-of-url}/filename
    // On the FIRST run: downloads from GitHub (needs internet)
    // On ALL SUBSEQUENT runs: reads directly from the local cache file (no internet!)
    ResourceCacheService cache = new ResourceCacheService();
    DefaultResourceLoader loader = new DefaultResourceLoader();

    // Load the tokenizer (0.7 MB JSON file defining the vocabulary)
    this.tokenizer = HuggingFaceTokenizer.newInstance(
            cache.getCachedResource(loader.getResource(TOKENIZER_URI)).getInputStream(),
            Map.of());

    // Create ONNX Runtime environment (one per JVM is sufficient)
    this.environment = OrtEnvironment.getEnvironment();

    // Load the model (86 MB ONNX file) into an inference session
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
        this.session = environment.createSession(
                cache.getCachedResource(loader.getResource(MODEL_URI)).getContentAsByteArray(),
                opts);
    }
}
```

**How `ResourceCacheService` works:**

`ResourceCacheService` (provided by the `spring-ai-transformers` module) is a cache-aside utility. It computes a deterministic UUID from the URL string, checks if `${tmpdir}/spring-ai-onnx-generative/{uuid}/filename` already exists, and returns the cached `Resource` if so — completely skipping the download. The UUID is computed once from the URL, so the same URL always maps to the same cache path across JVM restarts.

**The inference method (`call`):**

```java
@Override
public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> texts = request.getInstructions();

    // Step 1: Tokenize all texts in one batch
    Encoding[] encodings = tokenizer.batchEncode(texts.toArray(new String[0]));

    // Step 2: Extract token ID arrays for ONNX input tensors
    long[][] inputIds      = new long[encodings.length][];
    long[][] attentionMask = new long[encodings.length][];
    long[][] tokenTypeIds  = new long[encodings.length][];
    for (int i = 0; i < encodings.length; i++) {
        inputIds[i]      = encodings[i].getIds();
        attentionMask[i] = encodings[i].getAttentionMask();
        tokenTypeIds[i]  = encodings[i].getTypeIds();
    }

    // Step 3: Create ONNX tensors and run the model
    try (OnnxTensor inputIdsTensor  = OnnxTensor.createTensor(environment, inputIds);
         OnnxTensor maskTensor      = OnnxTensor.createTensor(environment, attentionMask);
         OnnxTensor typeIdsTensor   = OnnxTensor.createTensor(environment, tokenTypeIds);
         OrtSession.Result results  = session.run(Map.of(
                 "input_ids",      inputIdsTensor,
                 "attention_mask", maskTensor,
                 "token_type_ids", typeIdsTensor))) {

        // Step 4: Get the last_hidden_state output: shape [batch, seq_len, 384]
        float[][][] tokenEmbeddings =
            (float[][][]) results.get("last_hidden_state").get().getValue();

        // Step 5: Mean pooling in pure Java
        List<float[]> resultEmbeddings = new ArrayList<>();
        for (int b = 0; b < tokenEmbeddings.length; b++) {
            int seqLen = tokenEmbeddings[b].length;
            int dim    = tokenEmbeddings[b][0].length;   // 384
            float[] pooled = new float[dim];
            float maskSum  = 0f;

            for (int s = 0; s < seqLen; s++) {
                float m = attentionMask[b][s];            // 1 for real tokens, 0 for padding
                for (int d = 0; d < dim; d++) {
                    pooled[d] += tokenEmbeddings[b][s][d] * m;
                }
                maskSum += m;
            }

            // Divide by the number of real (non-padding) tokens
            float denom = Math.max(maskSum, 1e-9f);       // avoid division by zero
            for (int d = 0; d < dim; d++) {
                pooled[d] /= denom;
            }
            resultEmbeddings.add(pooled);
        }

        AtomicInteger idx = new AtomicInteger(0);
        return new EmbeddingResponse(
            resultEmbeddings.stream()
                .map(e -> new Embedding(e, idx.getAndIncrement()))
                .toList()
        );
    }
}
```

**Key technical points:**
- **Batch processing**: all texts are tokenized and embedded in a single ONNX session run — much faster than one-by-one
- **try-with-resources on OnnxTensor**: ONNX tensors hold native memory; the `try` block ensures they are freed when done
- **384 dimensions**: all-MiniLM-L6-v2 produces 384-dimensional embeddings (vs 1536 for OpenAI ada-002). Smaller but still excellent for semantic similarity

### 7.6 SimpleVectorStore — How Vectors Are Stored

```java
// AiConfig.java
@Bean
public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

`SimpleVectorStore` is Spring AI's in-memory vector store:

```java
// Simplified internal structure:
public class SimpleVectorStore {
    private final Map<String, Document> store = new ConcurrentHashMap<>();

    public void add(List<Document> documents) {
        for (Document doc : documents) {
            float[] embedding = embeddingModel.embed(doc);  // calls OnnxEmbeddingModel
            doc.setEmbedding(embedding);
            store.put(UUID.randomUUID().toString(), doc);
        }
    }

    public List<Document> similaritySearch(SearchRequest request) {
        float[] queryVector = embeddingModel.embed(request.getQuery());
        // Compute cosine similarity against ALL stored vectors
        // Filter by metadata expression (e.g. documentId == "xyz")
        // Return top-K results
    }
}
```

**Important:** This is **in-memory** — all vectors are lost when the app restarts. For production, replace `SimpleVectorStore` with a persistent vector database:

| Vector Database | Spring AI Config |
|---|---|
| Pinecone | `spring-ai-pinecone-store` |
| pgvector (PostgreSQL) | `spring-ai-pgvector-store` |
| Weaviate | `spring-ai-weaviate-store` |
| Milvus | `spring-ai-milvus-store` |

All implement the same `VectorStore` interface — you'd only change the bean in `AiConfig.java`.

### 7.7 Metadata Tagging — The documentId Filter

```java
// DocumentService.java — Step 3
String documentId = UUID.randomUUID().toString();

chunks.forEach(chunk ->
    chunk.getMetadata().put("documentId", documentId)
);
```

Each stored chunk looks like this:

```
Document {
    text:      "The contract shall be governed by the laws of...",
    embedding: [0.23, -0.11, 0.84, ...],   // float[384]
    metadata:  {
        "documentId": "a4f9b2d1-3e8c-4c7f-b091-2a8e7d5f1e3c",
        "source":     "contract.pdf"
    }
}
```

When you query with a specific `documentId`, the filter expression `"documentId == 'a4f9b2d1-...'"` limits the cosine similarity search to only the chunks from that document — even if dozens of other documents are also in the store.

---

## 8. RAG Query Pipeline — The Full Flow

`POST /documents/{documentId}/query` with `{"question": "What are the payment terms?"}`

### 8.1 Embedding the Question

```java
// QueryService.java
List<Document> relevantDocs = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(request.question())              // ← triggers embedding of the question
        .topK(topK)                             // return top 4 most similar chunks
        .filterExpression("documentId == '" + documentId + "'")
        .build()
);
```

The question goes through exactly the same pipeline as the document chunks:

```
"What are the payment terms?"
    → HuggingFaceTokenizer.encode()
    → OnnxEmbeddingModel.call()  (ONNX Runtime inference)
    → mean pooling
    → float[384] questionVector = [0.45, 0.12, -0.33, ...]

(All of this happens locally — no internet call, no API cost)
```

### 8.2 Cosine Similarity Search

Now we have:
- `questionVector` = the 384-dim embedding of the user's question
- `chunkVectors` = all stored embeddings for this document

`SimpleVectorStore` computes **cosine similarity** between the question vector and every chunk vector that matches the filter:

$$\text{cosine\_similarity}(A, B) = \frac{A \cdot B}{|A| \times |B|}$$

Where:
- $A \cdot B = \sum_i A_i B_i$ — the dot product
- $|A| = \sqrt{\sum_i A_i^2}$ — the magnitude (length) of vector A

Result is in $[-1, 1]$:
- $1.0$ = identical meaning
- $0.0$ = completely unrelated
- $-1.0$ = opposite meaning

**Why cosine similarity rather than Euclidean distance?**

Euclidean distance is affected by the magnitude (length) of vectors. Two vectors that point in the same direction but one is longer would appear "far" from each other by Euclidean measure. Cosine similarity only cares about the angle — the direction in semantic space — not the magnitude. This makes it robust for text similarity.

```
Example (simplified to 2D):

questionVector:  [0.60, 0.80]
chunkA:          [0.65, 0.75]   → cosine ≈ 0.999  ✓ HIGH similarity
chunkB:          [1.30, 1.50]   → cosine ≈ 0.999  ✓ Same direction, different magnitude
chunkC:          [-0.30, 0.70]  → cosine ≈ 0.62   ✗ Lower similarity
```

The top-K (default 4) chunks with the highest cosine similarity scores are returned.

### 8.3 Building the Augmented Prompt

```java
// QueryService.java
private static final String SYSTEM_PROMPT = """
    You are a helpful assistant that answers questions based on the provided context.
    Use ONLY the information from the context below to answer the question.
    If the context doesn't contain enough information to answer, say so clearly.
    Do not make up information that is not in the context.

    Context:
    {context}
    """;

// Build the context string from retrieved chunks
String context = relevantDocs.stream()
    .map(Document::getText)            // getText() is the Spring AI 1.1.4 API
    .collect(Collectors.joining("\n\n---\n\n"));
```

The context string is the concatenation of the top-4 retrieved chunks:

```
"The term of this agreement is three years. Payment terms are Net-30...

---

Payment must be received within 30 days of invoice date. Wire transfers...

---

Late payments incur a 2% monthly interest charge starting on day 31...

---

All invoices must be sent electronically to the billing department email..."
```

### 8.4 Calling Google Gemini

```java
// QueryService.java
ChatClient chatClient = ChatClient.builder(chatModel).build();

String answer = chatClient.prompt()
    .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
    .user(request.question())
    .call()
    .content();
```

**What actually gets sent to Gemini's API:**

The `ChatClient` translates the above into an HTTPS request to `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={API_KEY}`:

```json
{
  "contents": [
    {
      "role": "user",
      "parts": [{ "text": "What are the payment terms?" }]
    }
  ],
  "systemInstruction": {
    "parts": [{
      "text": "You are a helpful assistant...\n\nContext:\n[4 retrieved chunks]"
    }]
  },
  "generationConfig": {
    "temperature": 0.7
  }
}
```

**Gemini's response:**

```json
{
  "candidates": [{
    "content": {
      "parts": [{
        "text": "Based on the document, the payment terms are Net-30, meaning payment is due within 30 days of the invoice date. Late payments incur a 2% monthly interest charge starting on day 31. All invoices must be sent electronically to the billing department."
      }]
    }
  }]
}
```

Spring AI's `ChatClient.call().content()` extracts the text string from this response.

**The final response to the client:**

```json
{
  "answer": "Based on the document, the payment terms are Net-30...",
  "question": "What are the payment terms?",
  "documentId": "a4f9b2d1-...",
  "relevantChunks": [
    "The term of this agreement is three years. Payment terms are Net-30...",
    "Payment must be received within 30 days of invoice date...",
    "Late payments incur a 2% monthly interest charge...",
    "All invoices must be sent electronically..."
  ]
}
```

The `relevantChunks` field shows you exactly which parts of the document Gemini read before generating the answer.

---

## 9. Per-Document vs Cross-Document Query

### Per-Document Query

```
POST /documents/{documentId}/query
```

Filters the vector search to only chunks that belong to the specified document:

```java
SearchRequest.builder()
    .query(request.question())
    .topK(topK)
    .filterExpression("documentId == '" + documentId + "'")
    .build()
```

Use this when you want a precise answer from a specific document ("Tell me what clause 7 of this contract says").

### Cross-Document Query

```
POST /query
```

No filter expression — searches all stored chunks from all uploaded documents:

```java
SearchRequest.builder()
    .query(request.question())
    .topK(topK)
    .build()
// No filterExpression → searches across all documents
```

**Example with 3 uploaded documents:**

```
Upload: doc-A (finance report), doc-B (HR policy), doc-C (product specs)

POST /query  {"question": "What is the annual revenue?"}

Step 1: Embed "What is the annual revenue?"
Step 2: Cosine similarity search across ALL chunks (doc-A + doc-B + doc-C)
Step 3: Finance report chunks score highest → top-4 from doc-A retrieved
Step 4: Gemini answers from those chunks

The answer comes from whichever document(s) contained the most relevant text.
```

**Note:** The vector store is in-memory and loses all data on restart. Re-upload documents after restarting the application.

---

## 10. All Exposed API Endpoints

| Method | Path | Auth | Request | Response |
|--------|------|------|---------|----------|
| `POST` | `/auth/login` | None | `{"username":"admin","password":"admin123"}` | `{"token":"eyJ...","username":"admin"}` |
| `POST` | `/documents/upload` | JWT | `multipart/form-data` with `file` field | `{"documentId":"...","filename":"...","totalChunks":15,"message":"..."}` |
| `GET` | `/documents` | JWT | none | `[{"documentId":"...","filename":"...","chunks":15}]` |
| `POST` | `/documents/{id}/query` | JWT | `{"question":"..."}` | `{"answer":"...","question":"...","documentId":"...","relevantChunks":[...]}` |
| `POST` | `/query` | JWT | `{"question":"..."}` | Same as above (no documentId, searches all docs) |

**Server runs on port `8082`** (configured by `server.port=8082`).

**Example curl commands:**

```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

# 2. Upload a document
curl -X POST http://localhost:8082/documents/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@contract.pdf"

# 3. Query a specific document (replace DOC_ID with actual UUID from step 2)
curl -X POST http://localhost:8082/documents/$DOC_ID/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"What are the payment terms?"}'

# 4. List all uploaded documents
curl http://localhost:8082/documents \
  -H "Authorization: Bearer $TOKEN"

# 5. Query across all documents
curl -X POST http://localhost:8082/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the termination policy?"}'
```

**Error responses (from GlobalExceptionHandler):**

```json
// 400 Bad Request (validation)
{"error": "username: must not be blank", "timestamp": "2026-04-26T10:00:00"}

// 401 Unauthorized (wrong password)
{"error": "Invalid username or password", "timestamp": "2026-04-26T10:00:00"}

// 404 Not Found (documentId not found)
{"error": "Document not found: xyz-123", "timestamp": "2026-04-26T10:00:00"}

// 413 Payload Too Large (file > 10MB)
{"error": "File size exceeds the maximum allowed size (10MB)", "timestamp": "..."}

// 500 Internal Server Error
{"error": "An unexpected error occurred. Please try again later.", "timestamp": "..."}
```

---

## 11. Configuration — application.properties Explained

```properties
# ── Server ──────────────────────────────────────────────────
spring.application.name=spring-rag-app
server.port=8082
# Embedded Tomcat starts on port 8082 (not the default 8080)

# ── Google Gemini (Chat / Generation) ───────────────────────
spring.ai.google.genai.api-key=AIzaSy...
# Your Google AI Studio API key. Get one for free at: https://aistudio.google.com/
# In production: use environment variable SPRING_AI_GOOGLE_GENAI_API_KEY instead of hardcoding

spring.ai.google.genai.chat.options.model=gemini-2.0-flash
# The Gemini model to use for generation.
# gemini-2.0-flash = fast, cheap, excellent quality
# gemini-1.5-pro   = slower but higher reasoning capability

spring.ai.google.genai.chat.options.temperature=0.7
# 0.0 = very deterministic (same question → same answer)
# 1.0 = very creative/random
# 0.7 = balanced — good for factual RAG

# ── ONNX Embedding Model (Local) ────────────────────────────
spring.ai.embedding.transformer.enabled=false
# Disables Spring AI's default transformer embedding auto-configuration.
# We use our own OnnxEmbeddingModel bean defined in AiConfig.java instead.
spring.main.allow-bean-definition-overriding=true
# Allows our custom EmbeddingModel bean to override any conflicting auto-configured bean.

# ── JWT ─────────────────────────────────────────────────────
app.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
# 256-bit key in hex. In production: use environment variable or secrets manager.

app.jwt.expiration-ms=3600000
# 3,600,000 milliseconds = 1 hour. Tokens expire after 1 hour.

# ── File Upload ─────────────────────────────────────────────
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB

# ── RAG Tuning ──────────────────────────────────────────────
app.rag.chunk-size=800
# Tokens per chunk. Larger = more context per chunk, less precise retrieval.
# Smaller = more precise, but may split sentences mid-thought.

app.rag.chunk-overlap=100
# Tokens shared between adjacent chunks. Prevents information loss at boundaries.

app.rag.top-k=4
# Number of chunks to retrieve per query.
# More chunks = more context for Gemini but higher token cost and potential noise.
```

---

## 12. How Every Class Fits Together (Dependency Map)

```
Spring Boot App starts up
         │
         ├── AiConfig.java
         │     ├── creates: OnnxEmbeddingModel  @Bean @Primary
         │     │    ├─ ResourceCacheService (loads files from cache or downloads once)
         │     │    ├─ HuggingFaceTokenizer (from tokenizer.json)
         │     │    └─ OrtSession (from model.onnx via ONNX Runtime)
         │     └── creates: SimpleVectorStore(embeddingModel)
         │
         ├── UserConfig.java
         │     ├── creates: PasswordEncoder (BCryptPasswordEncoder)
         │     ├── creates: UserDetailsService (InMemoryUserDetailsManager)
         │     │            users: admin/admin123, user/user123
         │     └── creates: AuthenticationProvider (DaoAuthenticationProvider)
         │
         ├── JwtService.java  (@Service)
         │     └── reads: app.jwt.secret, app.jwt.expiration-ms
         │
         ├── JwtAuthenticationFilter.java (@Component)
         │     └── needs: JwtService, UserDetailsService
         │
         ├── SecurityConfig.java (@Configuration)
         │     └── needs: JwtAuthenticationFilter, AuthenticationProvider
         │     └── creates: SecurityFilterChain, AuthenticationManager
         │
         ├── AuthController.java (@RestController, path: /auth)
         │     └── needs: AuthenticationManager, JwtService, UserDetailsService
         │
         ├── DocumentService.java (@Service)
         │     └── needs: SimpleVectorStore
         │     └── owns: ConcurrentHashMap<documentId → DocumentInfo>
         │
         ├── DocumentController.java (@RestController, path: /documents)
         │     └── needs: DocumentService
         │
         ├── QueryService.java (@Service)
         │     └── needs: SimpleVectorStore, ChatModel (Gemini), DocumentService
         │     └── creates ChatClient per request (wraps ChatModel)
         │
         ├── QueryController.java (@RestController, path: /documents/{id}/query)
         │     └── needs: QueryService
         │
         ├── GlobalQueryController.java (@RestController, path: /query)
         │     └── needs: QueryService
         │
         └── GlobalExceptionHandler.java (@RestControllerAdvice)
               └── applied globally to all controllers
```

---

## 13. End-to-End Request Traces

### Trace 1: Login

```
POST /auth/login  {"username":"admin","password":"admin123"}

[Filter Chain]
  JwtAuthenticationFilter:
    Authorization header is null → skip → proceed to chain
  AuthorizationFilter:
    URI matches "/auth/**" → permitAll() → allowed without authentication

[Controller]
  AuthController.login()
    @Valid validates @NotBlank on username and password → passes

    authenticationManager.authenticate(UsernamePasswordAuthToken("admin","admin123"))
      → DaoAuthenticationProvider:
          UserDetailsService.loadUserByUsername("admin")  → UserDetails(admin, ROLE_ADMIN)
          BCryptPasswordEncoder.matches("admin123", "$2a$10$...")  → true ✓

    token = jwtService.generateToken(userDetails)
      → Jwts.builder().subject("admin")...signWith(secretKey).compact()
      → "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZG1pbi..."

[Response]
  200 OK: {"token":"eyJhbGci...","username":"admin"}
```

### Trace 2: Document Upload

```
POST /documents/upload
  Authorization: Bearer eyJhbGci...
  Content-Type: multipart/form-data
  Body: (PDF bytes)

[Filter Chain]
  JwtAuthenticationFilter:
    jwt = "eyJhbGci..."
    username = jwtService.extractUsername(jwt) → "admin"
    userDetails = loadUserByUsername("admin")
    jwtService.isTokenValid(jwt, userDetails) → true ✓
    SecurityContext.setAuthentication(adminToken)
  AuthorizationFilter:
    anyRequest().authenticated() → admin is authenticated → allowed ✓

[Controller → Service]
  documentId = "a4f9b2d1-3e8c-4c7f-b091-2a8e7d5f1e3c"

  PARSE: TikaDocumentReader reads PDF → "Contract between Company A..."
  CHUNK: TokenTextSplitter → 8 chunks of ~800 tokens each
  TAG:   each chunk.metadata["documentId"] = "a4f9b2d1-..."

  EMBED: vectorStore.add(chunks)
    → For each chunk:
         OnnxEmbeddingModel.call([chunkText])
           → HuggingFaceTokenizer.encode()  → token IDs
           → OrtSession.run()               → last_hidden_state [1, seqLen, 384]
           → mean pooling                   → float[384]
    → SimpleVectorStore stores 8 Document objects with their vectors
    (All local — no network call, no API cost)

  documentStore["a4f9b2d1-..."] = {documentId, "contract.pdf", 8 chunks}

[Response]
  200 OK: {"documentId":"a4f9b2d1-...","filename":"contract.pdf","totalChunks":8,"message":"Document indexed successfully"}
```

### Trace 3: Document Query

```
POST /documents/a4f9b2d1-.../query
  Authorization: Bearer eyJhbGci...
  {"question":"What are the payment terms?"}

[Filter Chain + Auth — same as Trace 2]

[Controller → Service]
  documentService.documentExists("a4f9b2d1-...") → true ✓

  EMBED QUESTION:
    OnnxEmbeddingModel.call(["What are the payment terms?"])
      → tokenize → ONNX inference → mean pool → float[384] questionVector

  SIMILARITY SEARCH:
    SimpleVectorStore iterates all chunks with documentId == "a4f9b2d1-...":
      Chunk 0 (parties):    cosine = 0.31  ✗
      Chunk 2 (payment):    cosine = 0.89  ✓ TOP
      Chunk 5 (term):       cosine = 0.28  ✗
      Chunk 6 (due dates):  cosine = 0.85  ✓ TOP
      Chunk 7 (late fees):  cosine = 0.78  ✓ TOP
      Chunk 3 (gov. law):   cosine = 0.22  ✗
      Chunk 1 (definitions):cosine = 0.65  ✓ 4th
    Returns top-4: [Chunk2, Chunk6, Chunk7, Chunk1]

  BUILD CONTEXT:
    context = Chunk2.text + "\n---\n" + Chunk6.text + "\n---\n" + Chunk7.text + ...

  CALL GEMINI:
    HTTPS POST to generativelanguage.googleapis.com:
      systemInstruction: "You are a helpful assistant... Context: [4 chunks]"
      user message:      "What are the payment terms?"

    Gemini responds:
      "Based on the document, the payment terms are Net-30..."

[Response]
  200 OK:
  {
    "answer":        "Based on the document, payment terms are Net-30...",
    "question":      "What are the payment terms?",
    "documentId":    "a4f9b2d1-...",
    "relevantChunks":["The term of this agreement...", "Payment due within 30 days...", ...]
  }
```

---

## Summary: The Complete Mental Model

```
Your PDF/DOCX/TXT                   Your Question
      │                                   │
      ▼                                   ▼
 [Apache Tika]                    [OnnxEmbeddingModel]
  extract text                     tokenize + ONNX inference
      │                            + mean pooling (pure Java)
      ▼                                   │
 [TokenTextSplitter]                      ▼
  800-token chunks             questionVector  float[384]
      │                                   │
      ▼                                   │
 [OnnxEmbeddingModel]                     │
  tokenize + ONNX inference               │
  + mean pooling (pure Java)              │
      │                                   │
      ▼                                   │
  chunkVector  float[384]                 │
      │                                   │
      ▼                                   │
 [SimpleVectorStore]  ◄──── cosine similarity ────┘
  in-memory HashMap         score each chunk
      │                     return top-4
      │                          │
      ▼                          ▼
  {chunk_text,          [top-4 relevant chunks]
   float[384],                   │
   metadata}                     ▼
                    system: "Answer using ONLY: [4 chunks]"
                    user:   "What are the payment terms?"
                                 │
                                 ▼
                       [Google Gemini 2.0 Flash]
                    (HTTPS call to Google's API)
                                 │
                                 ▼
                     Grounded, accurate answer
                     (no hallucination because
                      LLM can only use the context)
```

**The two cost dimensions:**
- **Embedding**: 100% free, runs locally on your CPU (no API call, no tokens, no quota)
- **Generation**: Calls Gemini API — uses your API quota. The free tier is generous for development.

**Why ONNX for embeddings?**

The `all-MiniLM-L6-v2` model is a lightweight (22M parameters) Transformer that was specifically distilled for producing high-quality sentence embeddings. The ONNX format is a portable, optimized model format that can be run by the ONNX Runtime library in Java — no Python, no PyTorch, no GPU required. The model loads in ~2 seconds and embeds text in milliseconds.

---

_This project is intentionally minimal and designed for learning. Production RAG systems add: persistent vector databases (pgvector, Milvus, Weaviate), chunking strategy tuning, reranking (a second model that re-scores retrieved chunks), query expansion, conversation history, streaming responses, and evaluation frameworks._
