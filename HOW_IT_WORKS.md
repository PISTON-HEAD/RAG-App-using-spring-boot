# Spring Boot RAG Application — Complete Technical Guide

> **Goal of this document:** Teach you everything about this project at the code level.  
> From an HTTP request arriving at the server, through authentication, document parsing, embedding, vector storage, similarity search, and LLM answer generation — every single step explained.

---

## Table of Contents

1. [What is RAG?](#1-what-is-rag)
2. [Project Overview & Package Structure](#2-project-overview--package-structure)
3. [How Spring Boot Starts Up](#3-how-spring-boot-starts-up)
4. [Authentication Layer — JWT Deep Dive](#4-authentication-layer--jwt-deep-dive)
   - 4.1 [What is JWT?](#41-what-is-jwt)
   - 4.2 [SecurityConfig & the Filter Chain](#42-securityconfig--the-filter-chain)
   - 4.3 [JwtService — Token Creation & Validation](#43-jwtservice--token-creation--validation)
   - 4.4 [JwtAuthenticationFilter — Request Interception](#44-jwtauthenticationfilter--request-interception)
   - 4.5 [AuthController — The Login Endpoint](#45-authcontroller--the-login-endpoint)
5. [What is Multipart? How Files Travel Over HTTP](#5-what-is-multipart-how-files-travel-over-http)
6. [Document Ingestion Pipeline — Step by Step](#6-document-ingestion-pipeline--step-by-step)
   - 6.1 [Apache Tika — Parsing Any File Type](#61-apache-tika--parsing-any-file-type)
   - 6.2 [Text Chunking — Why & How](#62-text-chunking--why--how)
   - 6.3 [What is a Vector / Embedding?](#63-what-is-a-vector--embedding)
   - 6.4 [How Text Becomes a Vector](#64-how-text-becomes-a-vector)
   - 6.5 [SimpleVectorStore — How Vectors Are Stored](#65-simplevectorstore--how-vectors-are-stored)
   - 6.6 [Metadata Tagging — The documentId Filter](#66-metadata-tagging--the-documentid-filter)
7. [RAG Query Pipeline — The Full Flow](#7-rag-query-pipeline--the-full-flow)
   - 7.1 [Embedding the Question](#71-embedding-the-question)
   - 7.2 [Cosine Similarity Search](#72-cosine-similarity-search)
   - 7.3 [Building the Augmented Prompt](#73-building-the-augmented-prompt)
   - 7.4 [Calling the LLM](#74-calling-the-llm)
8. [Your Question: documentId vs Free Query, Multi-doc Search](#8-your-question-documentid-vs-free-query-multi-doc-search)
9. [All Exposed API Endpoints](#9-all-exposed-api-endpoints)
10. [Configuration — application.properties Explained](#10-configuration--applicationproperties-explained)
11. [How Every Class Fits Together (Dependency Map)](#11-how-every-class-fits-together-dependency-map)
12. [End-to-End Request Traces](#12-end-to-end-request-traces)

---

## 1. What is RAG?

**RAG = Retrieval-Augmented Generation**

Without RAG, if you ask GPT-4 "What does clause 12 of my employment contract say?" it has no idea — it has never seen your contract and cannot make one up (well, it will hallucinate one, which is dangerous).

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

The LLM is not guessing. It is reading your documents and summarizing what it finds.

---

## 2. Project Overview & Package Structure

```
spring-rag-app/
├── pom.xml                              # Maven dependencies
└── src/main/java/com/ragapp/
    │
    ├── SpringRagApplication.java        # @SpringBootApplication entry point
    │
    ├── config/
    │   ├── AiConfig.java               # Wires up the in-memory vector store
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
    │   │                                # POST /query (cross-document)
    │   └── QueryService.java            # Similarity search → Prompt → LLM → Answer
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

| Dependency                                | Why We Need It                                                         |
| ----------------------------------------- | ---------------------------------------------------------------------- |
| `spring-boot-starter-web`                 | HTTP server (Tomcat), REST controllers, JSON serialization             |
| `spring-boot-starter-security`            | Security filter chain, `@EnableWebSecurity`, `AuthenticationManager`   |
| `spring-ai-openai-spring-boot-starter`    | Auto-configures `ChatModel` (GPT-4o-mini) + `EmbeddingModel` (ada-002) |
| `spring-ai-tika-document-reader`          | Apache Tika — extracts text from PDF, DOCX, TXT, HTML, etc.            |
| `jjwt-api` + `jjwt-impl` + `jjwt-jackson` | JWT token creation and parsing                                         |
| `spring-boot-starter-validation`          | `@NotBlank` and `@Valid` on request DTOs                               |

---

## 3. How Spring Boot Starts Up

```java
// SpringRagApplication.java
@SpringBootApplication
public class SpringRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}
```

`@SpringBootApplication` is three annotations in one:

- `@Configuration` — this class can define beans
- `@EnableAutoConfiguration` — Spring Boot reads pom.xml dependencies and auto-configures beans (e.g., sees `spring-ai-openai-spring-boot-starter` → creates `ChatModel` bean automatically)
- `@ComponentScan` — scans the `com.ragapp` package and all sub-packages for classes annotated with `@Service`, `@Component`, `@Controller`, `@Repository`

**Boot sequence:**

```
main() called
  └─ SpringApplication.run()
       ├─ Creates ApplicationContext
       ├─ Scans for @Component / @Service / @Controller classes
       ├─ Reads application.properties
       ├─ Runs AutoConfiguration (creates ChatModel, EmbeddingModel from OpenAI starter)
       ├─ Creates SimpleVectorStore (AiConfig.java)
       ├─ Creates UserDetailsService with admin/user (UserConfig.java)
       ├─ Creates JwtService, JwtAuthenticationFilter
       ├─ Builds SecurityFilterChain (SecurityConfig.java)
       └─ Starts embedded Tomcat on port 8080
```

---

## 4. Authentication Layer — JWT Deep Dive

### 4.1 What is JWT?

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
  "alg": "HS384"     ← signing algorithm
}

Part 2 — Payload (Base64 decoded):
{
  "sub": "admin",                   ← username (subject)
  "roles": [{"authority":"ROLE_ADMIN"}],
  "iat": 1777096386,                ← issued at (Unix timestamp)
  "exp": 1777100003                 ← expires at
}

Part 3 — Signature:
  HMAC-SHA384(base64(header) + "." + base64(payload), secretKey)
```

**Why is this secure?**

The signature is computed using a **secret key** that only the server knows. If anyone tampers with the payload (e.g., changes `"sub":"user"` to `"sub":"admin"`), the signature no longer matches and the server rejects it. No session storage needed on the server side — the token itself contains all the information.

### 4.2 SecurityConfig & the Filter Chain

```java
// SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, AuthenticationProvider authProvider) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // csrf protection is for browser sessions with cookies
            // we use stateless JWT so CSRF is not applicable

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()   // /auth/login needs no token
                .anyRequest().authenticated()              // everything else needs JWT
            )

            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                // STATELESS = never create a HttpSession
                // every request MUST carry its own JWT
            )

            .authenticationProvider(authProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            // Insert our JWT filter BEFORE Spring's default username/password filter

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
[JwtAuthenticationFilter]   ◄── Our custom filter runs here
[UsernamePasswordAuthenticationFilter]
[ExceptionTranslationFilter]
[AuthorizationFilter]        ◄── This checks if the request is authenticated
       │
       ▼
  Your Controller
```

`addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` means "insert JwtAuthenticationFilter at position just before the UsernamePasswordAuthenticationFilter". By the time `AuthorizationFilter` runs, our filter has already populated `SecurityContextHolder` with the authenticated user.

**UserConfig (separate class to break circular dependency):**

```java
@Configuration
public class UserConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
        // BCrypt is a slow, salted hashing algorithm
        // "admin123" stored as "$2a$10$xyz..." (different hash each time due to salt)
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var admin = User.builder()
            .username("admin")
            .password(encoder.encode("admin123"))  // hashed, never plain text
            .roles("ADMIN")
            .build();
        // stored in memory — no database
        return new InMemoryUserDetailsManager(admin, user);
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        return provider;
        // This is what Spring calls when AuthenticationManager.authenticate() is invoked
        // It loads the user, BCrypt-checks the password
    }
}
```

### 4.3 JwtService — Token Creation & Validation

```java
// JwtService.java
@Service
public class JwtService {

    @Value("${app.jwt.secret}")  // from application.properties
    private String secretKey;   // "404E635266556A586E..." (hex-encoded 256-bit key)

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;  // 3600000 = 1 hour

    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
            .subject(userDetails.getUsername())   // "admin" goes into "sub" claim
            .claims(Map.of("roles", userDetails.getAuthorities()))
            .issuedAt(new Date())                 // current time
            .expiration(new Date(System.currentTimeMillis() + expirationMs))  // 1 hour later
            .signWith(getSigningKey())             // HMAC-SHA384 with your secret
            .compact();                           // combine & base64url encode all 3 parts
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
        // decodes the payload, verifies signature, returns "sub" field
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (ExpiredJwtException e) {
            return false;  // token expired → invalid
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())   // uses the SAME secret key to verify signature
            .build()
            .parseSignedClaims(token)      // throws if signature mismatch or expired
            .getPayload();                 // returns the claims (payload)
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);  // creates a SecretKey object for JJWT
    }
}
```

### 4.4 JwtAuthenticationFilter — Request Interception

```java
// JwtAuthenticationFilter.java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // OncePerRequestFilter guarantees doFilterInternal is called EXACTLY once per request
    // Even if a filter chain is re-triggered internally, this won't run twice

    @Override
    protected void doFilterInternal(request, response, filterChain) {

        // Step 1: Read the Authorization header
        String authHeader = request.getHeader("Authorization");
        // Expected value: "Bearer eyJhbGci..."

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);  // skip, let Spring Security handle it
            return;
        }

        // Step 2: Extract just the token (remove "Bearer " prefix)
        String jwt = authHeader.substring(7);

        try {
            // Step 3: Decode the token and get the username from the "sub" claim
            String username = jwtService.extractUsername(jwt);

            // Step 4: Only proceed if there's no existing authentication in context
            // (prevents re-processing if something already authenticated this request)
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Step 5: Load full user details from InMemoryUserDetailsManager
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // Step 6: Validate token (username matches + not expired)
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // Step 7: Create an authentication object
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            userDetails,             // principal (the user object)
                            null,                    // credentials (null — already verified)
                            userDetails.getAuthorities()  // [ROLE_ADMIN] or [ROLE_USER]
                        );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // adds IP address, session ID to auth context — useful for audit logging

                    // Step 8: Store in SecurityContextHolder (thread-local storage)
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    // Now the request is "authenticated" — AuthorizationFilter will let it through
                }
            }
        } catch (Exception ignored) {
            // Tampered token, malformed token, wrong key — silently skip
            // The SecurityContext has no authentication → AuthorizationFilter rejects with 403
        }

        // Step 9: Continue down the filter chain
        filterChain.doFilter(request, response);
    }
}
```

### 4.5 AuthController — The Login Endpoint

```java
// AuthController.java
@PostMapping("/auth/login")
public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {

    // Step 1: @Valid triggers Jakarta validation
    // LoginRequest has @NotBlank on both fields
    // if blank → MethodArgumentNotValidException → GlobalExceptionHandler returns 400

    // Step 2: Authenticate username/password
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.username(), request.password())
    );
    // This calls DaoAuthenticationProvider (from UserConfig):
    //   1. Loads user by username from InMemoryUserDetailsManager
    //   2. BCrypt.check(input_password, stored_hash)
    //   3. If mismatch → throws BadCredentialsException → GlobalExceptionHandler returns 401

    // Step 3: If we get here, credentials are valid — generate JWT
    UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
    String token = jwtService.generateToken(userDetails);

    return ResponseEntity.ok(new LoginResponse(token, request.username()));
    // {"token": "eyJhbGci...", "username": "admin"}
}
```

---

## 5. What is Multipart? How Files Travel Over HTTP

When a browser or curl sends a file to a server, it cannot just put binary file bytes directly in the HTTP body (the body is text/JSON in normal requests). Instead, it uses a special format called **multipart/form-data**.

**Normal JSON request body:**

```http
POST /auth/login HTTP/1.1
Content-Type: application/json

{"username": "admin", "password": "admin123"}
```

**Multipart file upload:**

```http
POST /documents/upload HTTP/1.1
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxk

------WebKitFormBoundary7MA4YWxk
Content-Disposition: form-data; name="file"; filename="report.pdf"
Content-Type: application/pdf

(binary PDF bytes here)
------WebKitFormBoundary7MA4YWxk--
```

The `boundary` is a random separator string that marks where each "part" starts and ends. The HTTP body can have multiple parts — this is why it's called "multipart".

Spring Boot automatically parses this using `MultipartFile`:

```java
// DocumentController.java
@PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<DocumentUploadResponse> uploadDocument(
        @RequestParam("file") MultipartFile file) {
    // @RequestParam("file") tells Spring: find the part named "file" in the multipart body
    // MultipartFile gives you:
    //   file.getOriginalFilename()  → "report.pdf"
    //   file.getSize()              → 1048576 (bytes)
    //   file.getContentType()       → "application/pdf"
    //   file.getInputStream()       → raw bytes to read from
    //   file.isEmpty()              → false
}
```

**application.properties file size limits:**

```properties
spring.servlet.multipart.max-file-size=10MB       # max single file
spring.servlet.multipart.max-request-size=10MB    # max entire request
```

If exceeded, Spring throws `MaxUploadSizeExceededException`, which our `GlobalExceptionHandler` catches and returns a clean 413 response.

---

## 6. Document Ingestion Pipeline — Step by Step

When `POST /documents/upload` is called with a file, `DocumentService.ingestDocument()` runs through 5 steps:

```
MultipartFile (binary bytes)
      │
Step 1: PARSE  ─── Apache Tika ──► Plain text string
      │
Step 2: CHUNK  ─── TokenTextSplitter ──► List of text chunks (~800 tokens each)
      │
Step 3: TAG    ─── Add metadata: {documentId: "abc-123"} on each chunk
      │
Step 4: EMBED  ─── OpenAI text-embedding-ada-002 ──► Each chunk becomes a float[1536] vector
      │
Step 5: STORE  ─── SimpleVectorStore.add() ──► Vectors stored in memory HashMap
      │
      └── Return: {documentId, filename, totalChunks, "indexed successfully"}
```

### 6.1 Apache Tika — Parsing Any File Type

```java
// DocumentService.java — Step 1
TikaDocumentReader reader = new TikaDocumentReader(
    new InputStreamResource(file.getInputStream())
    // InputStreamResource wraps the raw byte stream from MultipartFile
);
List<Document> rawDocuments = reader.get();
```

**What is Apache Tika?**

Tika is a toolkit that can detect the type of virtually any file and extract its text content. It handles:

| File Type | What Tika does                                        |
| --------- | ----------------------------------------------------- |
| `.pdf`    | Uses PDFBox to extract text from each page            |
| `.docx`   | Unzips the Office Open XML, reads `word/document.xml` |
| `.txt`    | Reads as-is                                           |
| `.html`   | Parses HTML tags, extracts visible text               |
| `.xlsx`   | Reads cell values                                     |
| `.pptx`   | Reads slide text                                      |

The result is a `List<Document>` where `Document` is Spring AI's wrapper:

```java
// Spring AI Document
new Document(
    "This is the extracted text content...",  // getContent()
    Map.of("source", "report.pdf")             // getMetadata()
);
```

### 6.2 Text Chunking — Why & How

**Why can't we just embed the whole document?**

1. **Token limits**: OpenAI's embedding model accepts at most ~8191 tokens. A 50-page PDF is ~25,000 tokens.
2. **Precision**: If you embed the entire document as one vector, the vector is an "average" of everything. When you search for a specific detail, it gets drowned out by everything else.
3. **Context window**: When we retrieve chunks to send to GPT-4o-mini, the model has a limited context window. We need small, focused pieces of text, not the whole document.

**The chunking code:**

```java
// DocumentService.java — Step 2
TokenTextSplitter splitter = new TokenTextSplitter(
    chunkSize,    // 800 — target number of tokens per chunk
    chunkOverlap, // 100 — overlap between consecutive chunks
    5,            // minimum chunk size in tokens (too small = useless)
    10000,        // maximum chunk size in tokens
    true          // keep separator characters
);
List<Document> chunks = splitter.apply(rawDocuments);
```

**What is a "token"?** Not a word — a token is roughly 4 characters. "Hello world" = 2 tokens. "Retrieval-Augmented Generation" ≈ 5 tokens. OpenAI's tokenizer uses Byte-Pair Encoding (BPE).

**Visual example of chunking with overlap:**

```
Original text (simplified):
"The quick brown fox jumps over the lazy dog. It was a sunny afternoon.
The fox decided to rest. The dog was not amused. Evening came slowly."

chunkSize=4 words, overlap=2 words (simplified):

Chunk 1: "The quick brown fox"
Chunk 2: "brown fox jumps over"   ← 2 words overlap with chunk 1
Chunk 3: "jumps over the lazy"    ← 2 words overlap with chunk 2
Chunk 4: "the lazy dog It"
Chunk 5: "dog It was a"
...
```

**Why overlap?** If a sentence or paragraph is cut at a boundary, the 100-token overlap ensures the thought is complete in at least one chunk. Without overlap, you might cut "The contract is void if... [CHUNK BREAK] ...the payment is not received within 30 days" and neither chunk tells the full story.

### 6.3 What is a Vector / Embedding?

A **vector** is just a list of numbers. For example:

```
[0.23, -0.11, 0.84, 0.07, -0.52, ...]  ← 1536 numbers for ada-002
```

An **embedding** is a vector that encodes the _meaning_ of text. The OpenAI model has learned to assign similar vectors to text with similar meanings:

```
"The cat sat on the mat"  → [0.23, -0.11, 0.84, ...]
"A feline rested on a rug" → [0.24, -0.10, 0.82, ...]   ← very similar!
"The stock market crashed"  → [0.91,  0.53, -0.2, ...]   ← very different
```

This is the key insight of the entire RAG system: **semantic similarity = vector proximity**.

You don't need to find the exact same words. If you ask "What are the adverse effects?" and the document says "side effects include...", the embeddings of those two phrases will be close together even though the words are different.

### 6.4 How Text Becomes a Vector

```java
// AiConfig.java — this creates the vector store
@Bean
public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
    return new SimpleVectorStore(embeddingModel);
    // embeddingModel is auto-configured from spring-ai-openai-spring-boot-starter
    // it wraps the OpenAI text-embedding-ada-002 HTTP API
}
```

When `vectorStore.add(chunks)` is called:

```
Spring AI calls EmbeddingModel.embedAll(chunks)
      │
      ▼
HTTP POST to https://api.openai.com/v1/embeddings
Body: {
  "model": "text-embedding-ada-002",
  "input": [
    "First chunk text goes here...",
    "Second chunk text goes here...",
    "Third chunk text goes here..."
  ]
}
      │
      ▼
OpenAI Response:
{
  "data": [
    {"index": 0, "embedding": [0.23, -0.11, 0.84, ...1536 numbers...]},
    {"index": 1, "embedding": [0.51,  0.02, -0.3, ...1536 numbers...]},
    {"index": 2, "embedding": [-0.1,  0.77,  0.4, ...1536 numbers...]}
  ]
}
      │
      ▼
Stored in memory: [ {chunk_text, embedding_vector, metadata}, ... ]
```

All 1536 numbers are just floating-point values between roughly -1 and 1. The model learned through training on vast amounts of text that "certain geometric positions in 1536-dimensional space" correspond to "certain types of meaning".

### 6.5 SimpleVectorStore — How Vectors Are Stored

```java
// Spring AI's SimpleVectorStore (simplified internal structure)
public class SimpleVectorStore {
    // in-memory: document id → {content, embedding float[], metadata Map}
    private final Map<String, Document> store = new ConcurrentHashMap<>();

    public void add(List<Document> documents) {
        for (Document doc : documents) {
            // 1. Call EmbeddingModel to compute the vector
            float[] embedding = embeddingModel.embed(doc);
            // 2. Attach the vector to the document
            doc.setEmbedding(embedding);
            // 3. Store in the HashMap with a UUID key
            store.put(UUID.randomUUID().toString(), doc);
        }
    }
}
```

This is **in-memory** which is why data is lost when the app restarts. For production you would use a real vector database like:

- **Pinecone** — cloud vector database
- **Weaviate** — open source vector database
- **pgvector** — PostgreSQL extension for vectors
- **Milvus** — high-performance vector database

Spring AI supports all of these through the same `VectorStore` interface — you'd just change the bean in `AiConfig.java`.

### 6.6 Metadata Tagging — The documentId Filter

```java
// DocumentService.java — Step 3
String documentId = UUID.randomUUID().toString();
// e.g.: "a4f9b2d1-3e8c-4c7f-b091-2a8e7d5f1e3c"

chunks.forEach(chunk ->
    chunk.getMetadata().put("documentId", documentId)
);
```

Each chunk now looks like this in the store:

```
Document {
    content: "The contract shall be governed by...",
    embedding: [0.23, -0.11, 0.84, ...],
    metadata: {
        "documentId": "a4f9b2d1-...",
        "source": "contract.pdf"
    }
}
```

This is critical for filtering. When the user uploads 10 documents, the vector store has hundreds of chunks from all of them. The `documentId` tag lets us say "when querying about doc A, only consider chunks from doc A" — not chunks from other documents.

---

## 7. RAG Query Pipeline — The Full Flow

`POST /documents/{documentId}/query` with `{"question": "What are the payment terms?"}`

```java
// QueryService.java
public QueryResponse query(String documentId, QueryRequest request) {

    // Guard check
    if (!documentService.documentExists(documentId)) {
        throw new IllegalArgumentException("Document not found: " + documentId);
        // → GlobalExceptionHandler → 404 response
    }

    // Steps 1-4 below...
}
```

### 7.1 Embedding the Question

```java
// Step 1: The question is embedded using the SAME model that embedded the chunks
// (MUST be same model — vectors are only comparable if from same embedding space)

String filterExpression = "documentId == '" + documentId + "'";
List<Document> relevantDocs = vectorStore.similaritySearch(
    SearchRequest.query(request.question())   // ← this triggers embedding of the question
        .withTopK(topK)                       // return top 4 most similar chunks
        .withFilterExpression(filterExpression)  // only from this document
);
```

Internally, `SearchRequest.query(question)` causes:

```
"What are the payment terms?"
    → EmbeddingModel.embed("What are the payment terms?")
    → HTTP call to OpenAI ada-002
    → float[] questionVector = [0.45, 0.12, -0.33, ...]
```

### 7.2 Cosine Similarity Search

Now we have:

- `questionVector` = the embedding of the user's question
- `chunkVectors` = all the embeddings stored for this document

SimpleVectorStore computes **cosine similarity** between the question vector and every chunk vector:

```
cosine_similarity(A, B) = (A · B) / (|A| × |B|)

Where:
  A · B = sum of (A[i] × B[i]) for all i   (dot product)
  |A|   = sqrt(sum of A[i]²)               (magnitude of A)

Result is between -1 and 1:
  1.0  = identical meaning
  0.0  = completely unrelated
  -1.0 = opposite meaning
```

**Why cosine similarity and not Euclidean distance?**

Euclidean distance measures the straight-line distance between two points in 1536-dimensional space. It is affected by the _magnitude_ of vectors. Cosine similarity only cares about the _angle_ between vectors — the direction in semantic space, not the length. This makes it more robust for text.

```
Example (simplified to 2D for illustration):

questionVector:  [0.6, 0.8]    (points in some direction)
chunkA:          [0.65, 0.75]  (similar direction → high cosine similarity = 0.99)
chunkB:          [1.2, 1.6]    (same direction but longer → same cosine = 0.99)
chunkC:          [-0.3, 0.7]   (different direction → lower cosine = ~0.62)
```

The top-K (top 4) chunks with highest cosine similarity are returned.

### 7.3 Building the Augmented Prompt

```java
// Step 2: Build context string from the retrieved chunks
String context = relevantDocs.stream()
    .map(Document::getContent)
    .collect(Collectors.joining("\n\n---\n\n"));

// Example context string built:
// "Payment is due within 30 days of invoice date...
//  ---
//  Late payments incur a 2% monthly interest charge...
//  ---
//  All payments must be made in USD..."
```

```java
// Step 3: Build the full prompt
private static final String SYSTEM_PROMPT = """
    You are a helpful assistant that answers questions based on the provided context.
    Use ONLY the information from the context below to answer the question.
    If the context doesn't contain enough information to answer, say so clearly.
    Do not make up information that is not in the context.

    Context:
    {context}
    """;

String answer = chatClient.prompt()
    .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
    // Replaces {context} placeholder with the actual retrieved chunks
    .user(request.question())
    // The user's original question
    .call()
    .content();
```

**What actually gets sent to OpenAI's Chat API:**

```json
{
  "model": "gpt-4o-mini",
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful assistant that answers questions based on the provided context.\nUse ONLY the information from the context below to answer the question.\n...\n\nContext:\nPayment is due within 30 days...\n---\nLate payments incur a 2%...\n---\nAll payments must be in USD..."
    },
    {
      "role": "user",
      "content": "What are the payment terms?"
    }
  ]
}
```

The `system` message is instruction to the LLM about how to behave. The `user` message is the actual question. By putting the retrieved document chunks inside the `system` message as context, the LLM reads them before generating its answer.

### 7.4 Calling the LLM

OpenAI's GPT-4o-mini processes both messages and generates:

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "Based on the document, the payment terms are as follows: payment is due within 30 days of the invoice date. Late payments incur a 2% monthly interest charge. All payments must be made in USD."
      }
    }
  ]
}
```

Spring AI's `ChatClient.call().content()` extracts just the `"content"` string from this response.

**The final response returned to the client:**

```json
{
  "answer": "Based on the document, the payment terms are as follows: payment is due within 30 days of the invoice date...",
  "question": "What are the payment terms?",
  "documentId": "a4f9b2d1-...",
  "relevantChunks": [
    "Payment is due within 30 days of invoice date. The...",
    "Late payments incur a 2% monthly interest charge p...",
    "All payments must be made in USD. Wire transfers ar..."
  ]
}
```

The `relevantChunks` field is the first 200 characters of each retrieved chunk — it lets you see exactly which parts of the document the answer was based on.

---

## 8. Your Question: documentId vs Free Query, Multi-doc Search

### Why does the current query endpoint need a documentId?

```
POST /documents/{documentId}/query
```

The current design always filters by `documentId`:

```java
String filterExpression = "documentId == '" + documentId + "'";
List<Document> relevantDocs = vectorStore.similaritySearch(
    SearchRequest.query(request.question())
        .withTopK(topK)
        .withFilterExpression(filterExpression)  // ← only searches THIS document's chunks
);
```

**This means:** If you uploaded 3 documents:

- doc-A: "Company policy document"
- doc-B: "Employment contract"
- doc-C: "Benefits handbook"

And you query with doc-B's ID asking "What is the vacation policy?" — it will ONLY look through chunks from doc-B. Even if doc-C has a detailed vacation policy section, it won't find it.

### Why is per-document querying sometimes useful?

- You want a precise answer from a _specific_ document ("Tell me what clause 7 of this contract says")
- You uploaded competing vendor proposals and want to ask each one independently
- You want to avoid interference between unrelated documents

### Cross-Document Query (now supported)

The updated `QueryService` and `QueryController` now support **both modes**:

```
POST /documents/{documentId}/query  ← search only in one document
POST /query                          ← search across ALL documents
```

When querying across all documents, the filter expression is simply removed — the vector store searches all stored chunks regardless of which document they came from.

**When you send 3-4 docs and ask a general question:**

```
Upload: doc-A (finance report), doc-B (HR policy), doc-C (product specs)

POST /query  {"question": "What is the annual revenue?"}

Step 1: Embed "What is the annual revenue?"
Step 2: Search ALL chunks (doc-A + doc-B + doc-C combined)
Step 3: Cosine similarity finds "revenue" chunks from doc-A → most relevant
Step 4: LLM answers from those cross-document chunks

Result: The answer comes from whichever document(s) contained the most relevant text
```

**Important caveat:** If doc-A says revenue is $5M and doc-C also mentions revenue as $3M (for a different product line), the LLM will receive both chunks and needs to reconcile them. The system prompt "use ONLY the context" handles this — the LLM will present both and acknowledge the discrepancy rather than picking one arbitrarily.

---

## 9. All Exposed API Endpoints

| Method | Path                    | Auth | Request                                      | Response                                                                      |
| ------ | ----------------------- | ---- | -------------------------------------------- | ----------------------------------------------------------------------------- |
| `POST` | `/auth/login`           | None | `{"username":"admin","password":"admin123"}` | `{"token":"eyJ...","username":"admin"}`                                       |
| `POST` | `/documents/upload`     | JWT  | `multipart/form-data` with `file` field      | `{"documentId":"...","filename":"...","totalChunks":15,"message":"..."}`      |
| `GET`  | `/documents`            | JWT  | none                                         | `[{"documentId":"...","filename":"...","chunks":15}]`                         |
| `POST` | `/documents/{id}/query` | JWT  | `{"question":"..."}`                         | `{"answer":"...","question":"...","documentId":"...","relevantChunks":[...]}` |
| `POST` | `/query`                | JWT  | `{"question":"..."}`                         | Same as above but searches all docs                                           |

**Error responses (from GlobalExceptionHandler):**

```json
// 400 Bad Request (validation)
{"error": "username: must not be blank", "timestamp": "2026-04-25T11:00:00"}

// 401 Unauthorized (wrong password)
{"error": "Invalid username or password", "timestamp": "2026-04-25T11:00:00"}

// 404 Not Found (documentId not found)
{"error": "Document not found: xyz-123", "timestamp": "2026-04-25T11:00:00"}

// 413 Payload Too Large (file > 10MB)
{"error": "File size exceeds the maximum allowed size (10MB)", "timestamp": "..."}
```

---

## 10. Configuration — application.properties Explained

```properties
# ── Server ──────────────────────────────
server.port=8080
# The embedded Tomcat starts on port 8080

# ── OpenAI ──────────────────────────────
spring.ai.openai.api-key=${OPENAI_API_KEY:your-api-key-here}
# ${ENV_VAR:default} syntax: use env variable OPENAI_API_KEY, or fallback to literal
# NEVER hardcode real keys here (they'd be committed to git)

spring.ai.openai.chat.options.model=gpt-4o-mini
# gpt-4o-mini: fast, cheap. Change to gpt-4o for higher quality

spring.ai.openai.chat.options.temperature=0.7
# 0.0 = very deterministic, same question → same answer every time
# 1.0 = very creative/random
# 0.7 = balanced — good for factual RAG

spring.ai.openai.embedding.options.model=text-embedding-ada-002
# The embedding model — DO NOT change this after storing vectors
# Vectors from different models are not comparable

# ── JWT ──────────────────────────────────
app.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
# A 256-bit key in hex. In production: use environment variable or secrets manager.
# NEVER commit a real production key to source control.

app.jwt.expiration-ms=3600000
# 3600000 milliseconds = 1 hour. Tokens expire after 1 hour.

# ── File Upload ──────────────────────────
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB

# ── RAG Tuning ───────────────────────────
app.rag.chunk-size=800
# Tokens per chunk. Larger = more context per chunk but less precise retrieval.
# Smaller = more precise but may split sentences mid-thought.

app.rag.chunk-overlap=100
# Tokens shared between adjacent chunks. Prevents information loss at boundaries.

app.rag.top-k=4
# How many chunks to retrieve per query.
# More chunks = more context for LLM but higher token cost and potential noise.
```

---

## 11. How Every Class Fits Together (Dependency Map)

```
Spring Boot App starts up
         │
         ├── AiConfig.java
         │     └── creates: SimpleVectorStore(EmbeddingModel)
         │                  EmbeddingModel ← auto-configured by OpenAI starter
         │
         ├── UserConfig.java
         │     └── creates: PasswordEncoder (BCrypt)
         │     └── creates: UserDetailsService (InMemoryUserDetailsManager)
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
         ├── AuthController.java (@RestController)
         │     └── needs: AuthenticationManager, JwtService, UserDetailsService
         │
         ├── DocumentService.java (@Service)
         │     └── needs: SimpleVectorStore
         │     └── owns: ConcurrentHashMap<documentId, DocumentInfo>
         │
         ├── DocumentController.java (@RestController)
         │     └── needs: DocumentService
         │
         ├── QueryService.java (@Service)
         │     └── needs: SimpleVectorStore, ChatModel, DocumentService
         │     └── creates: ChatClient (wraps ChatModel)
         │
         ├── QueryController.java (@RestController)
         │     └── needs: QueryService
         │
         └── GlobalExceptionHandler.java (@RestControllerAdvice)
               └── applied globally to all controllers
```

---

## 12. End-to-End Request Traces

### Trace 1: Login

```
curl -X POST /auth/login -d '{"username":"admin","password":"admin123"}'

[HTTP Layer]
  Tomcat receives request → passes to Spring DispatcherServlet

[Filter Chain]
  JwtAuthenticationFilter:
    authHeader == null → skip → proceed to chain
  AuthorizationFilter:
    request URI is "/auth/login" → matches permitAll() → allowed without auth

[Controller]
  AuthController.login() called
    @Valid → validates NotBlank on username and password → passes
    authenticationManager.authenticate(UsernamePasswordAuthToken("admin","admin123"))
      → DaoAuthenticationProvider.authenticate()
        → UserDetailsService.loadUserByUsername("admin")
        → BCryptPasswordEncoder.matches("admin123", "$2a$10$xyz...")
        → matches! → returns authenticated token

    UserDetails = loadUserByUsername("admin")
    token = jwtService.generateToken(userDetails)
      → Jwts.builder().subject("admin").expiration(+1hr).signWith(key).compact()
      → "eyJhbGci..."

[Response]
  200 OK: {"token":"eyJhbGci...","username":"admin"}
```

### Trace 2: Document Upload

```
curl -X POST /documents/upload \
  -H "Authorization: Bearer eyJhbGci..." \
  -F "file=@contract.pdf"

[Filter Chain]
  JwtAuthenticationFilter:
    authHeader = "Bearer eyJhbGci..."
    jwt = "eyJhbGci..."
    username = jwtService.extractUsername(jwt) → "admin"
    userDetails = loadUserByUsername("admin")
    jwtService.isTokenValid(jwt, userDetails) → true
    SecurityContext.setAuthentication(adminToken) ← user is now authenticated

  AuthorizationFilter:
    anyRequest().authenticated() → admin is authenticated → allowed

[Controller]
  DocumentController.uploadDocument() called
    file.isEmpty() → false → proceed
    documentService.ingestDocument(file)

[Service]
  documentId = "a4f9b2d1-3e8c-4c7f-b091-2a8e7d5f1e3c" (random UUID)

  Step 1 PARSE:
    TikaDocumentReader reads PDF bytes → "Contract between Company A and Company B..."
    rawDocuments = [Document("Contract between Company A...")]

  Step 2 CHUNK:
    splitter.apply(rawDocuments)
    Chunk 0: "Contract between Company A and Company B, dated January 1..."
    Chunk 1: "dated January 1, 2025. The term of this agreement is..."
    Chunk 2: "agreement is three years from execution. Payment terms..."
    ... (say 8 chunks total)

  Step 3 TAG:
    Each chunk.metadata["documentId"] = "a4f9b2d1-..."

  Step 4 EMBED:
    HTTP POST to OpenAI /v1/embeddings with 8 chunk texts
    Returns 8 vectors, each float[1536]

  Step 5 STORE:
    SimpleVectorStore.add(chunks)
    Internal HashMap: {uuid1 → Chunk0+vector, uuid2 → Chunk1+vector, ...}

  documentStore["a4f9b2d1-..."] = {documentId, "contract.pdf", 8 chunks}

[Response]
  200 OK: {"documentId":"a4f9b2d1-...","filename":"contract.pdf","totalChunks":8,"message":"..."}
```

### Trace 3: Query

```
curl -X POST /documents/a4f9b2d1-.../query \
  -H "Authorization: Bearer eyJhbGci..." \
  -d '{"question":"What are the payment terms?"}'

[Filter Chain + Auth] (same as trace 2)

[Controller]
  QueryController.queryDocument("a4f9b2d1-...", QueryRequest("What are the payment terms?"))
    queryService.query(documentId, request)

[Service]
  documentService.documentExists("a4f9b2d1-...") → true

  Step 1 SIMILARITY SEARCH:
    SearchRequest.query("What are the payment terms?")
      → embed("What are the payment terms?")
      → HTTP call to OpenAI ada-002
      → questionVector = [0.45, 0.12, -0.33, ...]

    SimpleVectorStore iterates all stored chunks:
      For each chunk where metadata["documentId"] == "a4f9b2d1-...":
        score = cosineSimilarity(questionVector, chunkVector)

      Scores:
        Chunk 0 (contract parties): 0.31  ← low similarity
        Chunk 2 (payment terms):    0.89  ← HIGH similarity! ✓
        Chunk 5 (termination):      0.28  ← low
        Chunk 6 (payment due):      0.85  ← HIGH similarity! ✓
        Chunk 7 (late fees):        0.78  ← high similarity ✓
        Chunk 3 (governing law):    0.22  ← low

      Top-4: Chunk2(0.89), Chunk6(0.85), Chunk7(0.78), Chunk4(0.65)

  Step 2 BUILD CONTEXT:
    context = "agreement is three years from execution. Payment terms are Net-30...\n---\n
               Payment due within 30 days of invoice...\n---\n
               Late payments incur 2% monthly interest...\n---\n
               ..."

  Step 3 CALL LLM:
    HTTP POST to OpenAI /v1/chat/completions:
    {
      "model": "gpt-4o-mini",
      "messages": [
        {"role":"system", "content":"You are a helpful assistant...Context:\n[4 chunks]"},
        {"role":"user", "content":"What are the payment terms?"}
      ]
    }

    OpenAI responds:
    "Based on the contract, payment terms are Net-30, meaning payment is due within
     30 days of invoice date. Late payments accrue 2% monthly interest. All
     payments must be made in USD."

[Response]
  200 OK:
  {
    "answer": "Based on the contract, payment terms are Net-30...",
    "question": "What are the payment terms?",
    "documentId": "a4f9b2d1-...",
    "relevantChunks": [
      "agreement is three years from execution. Payment terms are Net-30...",
      "Payment due within 30 days of invoice date. Wire transfer...",
      "Late payments incur 2% monthly interest. This applies...",
      "invoices are sent electronically to the billing address..."
    ]
  }
```

---

## Summary: The Complete Mental Model

```
Your PDF/DOCX                    Your Question
     │                                │
     ▼                                ▼
[Tika] extract text          [OpenAI ada-002] embed question
     │                                │
     ▼                                ▼
[Splitter] 800-token chunks    questionVector [0.45, 0.12, ...]
     │
     ▼
[OpenAI ada-002] embed each chunk
     │
     ▼
[SimpleVectorStore] store {chunk_text, float[1536], metadata}

─────────────────────────── AT QUERY TIME ───────────────────────────

questionVector ──► cosine similarity against all chunk vectors
                         │
                         ▼
               top-4 most similar chunks
                         │
                         ▼
        system prompt: "Answer using ONLY this context: [4 chunks]"
        user turn:     "What are the payment terms?"
                         │
                         ▼
                   [GPT-4o-mini]
                         │
                         ▼
           Grounded, accurate, no hallucination
```

The power of RAG is in this combination:

- **Embeddings** handle semantic similarity (synonyms, paraphrases)
- **Vector search** retrieves the right pieces at scale
- **LLM** synthesizes retrieved pieces into a coherent natural-language answer
- **Metadata filtering** keeps documents isolated or allows cross-document search

---

_This project is intentionally minimal and designed for learning. Production RAG systems add: persistent vector databases, chunking strategy tuning, reranking (a second model that re-scores retrieved chunks), query expansion, conversation history, and evaluation frameworks._
