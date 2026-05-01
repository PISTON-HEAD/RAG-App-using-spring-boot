# Spring RAG App — Complete Technical Deep Dive

> Personal interview reference. Everything you need to explain this project confidently.
> This document covers both the original RAG app AND every change made when implementing conversational memory.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Technology Stack](#2-technology-stack)
3. [Application Architecture](#3-application-architecture)
4. [Document Ingestion Pipeline](#4-document-ingestion-pipeline)
5. [ONNX Embeddings — What, Why, and How](#5-onnx-embeddings--what-why-and-how)
6. [Vector Similarity Search — How It Works](#6-vector-similarity-search--how-it-works)
7. [RAG Query Pipeline](#7-rag-query-pipeline)
8. [JWT Authentication — How It Works](#8-jwt-authentication--how-it-works)
9. [Conversational Memory — The New Feature](#9-conversational-memory--the-new-feature)
10. [ChatHistoryService — Complete Code Walkthrough](#10-chathistoryservice--complete-code-walkthrough)
11. [QueryService — How Conversational Memory Is Wired In](#11-queryservice--how-conversational-memory-is-wired-in)
12. [Java Records — How ChatTurn Works Internally](#12-java-records--how-chatturn-works-internally)
13. [ConcurrentHashMap vs HashMap — Deep Dive](#13-concurrenthashmap-vs-hashmap--deep-dive)
14. [Why Deque? How It Stores Q&A](#14-why-deque-how-it-stores-qa)
15. [Why synchronized on the Deque?](#15-why-synchronized-on-the-deque)
16. [computeIfAbsent — Thread Safety Guarantee](#16-computeifabsent--thread-safety-guarantee)
17. [Session ID — Generation, Lifecycle, Expiry](#17-session-id--generation-lifecycle-expiry)
18. [How Gemini Receives the Full Conversation](#18-how-gemini-receives-the-full-conversation)
19. [Why 5 Turns? Why Not Unlimited?](#19-why-5-turns-why-not-unlimited)
20. [What Happens When a Session Expires?](#20-what-happens-when-a-session-expires)
21. [Spring @Scheduled — How TTL Cleanup Works](#21-spring-scheduled--how-ttl-cleanup-works)
22. [Spring Security Filter Chain](#22-spring-security-filter-chain)
23. [Exception Handling Strategy](#23-exception-handling-strategy)
24. [Actuator — Monitoring & Observability](#24-actuator--monitoring--observability)
25. [Configuration Properties Reference](#25-configuration-properties-reference)
26. [Complete Request Flow — End to End](#26-complete-request-flow--end-to-end)
27. [What Changed: Base RAG vs Conversational RAG](#27-what-changed-base-rag-vs-conversational-rag)
28. [In-Memory Data Structure — What Lives in RAM](#28-in-memory-data-structure--what-lives-in-ram)
29. [Interview Questions & Model Answers](#29-interview-questions--model-answers)

---

## 1. Project Overview

This is a **Spring Boot RAG (Retrieval Augmented Generation) application** with **conversational memory**.

### What the original RAG app did (before conversational memory)

The base version of this application was a stateless document Q&A system. Every single query was completely independent:

1. User logs in → gets a JWT token
2. User uploads a document → it gets parsed, chunked, embedded, and stored in an in-memory vector store
3. User asks a question → the question is embedded, the top-4 most similar document chunks are retrieved, fed to Gemini as context, and an answer is returned
4. **Each question was completely isolated** — Gemini had zero knowledge of any previous questions in the conversation

This was functional but had a major limitation: users couldn't have a **conversation**. If you asked "What are the payment terms?" and then followed up with "Can you elaborate on that?", Gemini would have no idea what "that" referred to.

### What we added: Conversational Memory

We added **server-side session management** — every conversation gets a UUID session ID, and the server stores the Q&A history for that session. On every new query, the server:

1. Looks up the session's prior Q&A turns
2. Injects them into the Gemini prompt as alternating User/Assistant messages
3. Appends the new Q&A turn to the session after getting the answer

Now Gemini sees the full context of the conversation — it can understand "that", "it", "what you said earlier", multi-turn clarification, etc.

### What it does now (full feature set)

Users upload documents (PDF, DOCX, TXT, etc.). They can then ask natural language questions in a **conversation**. The system:

1. Converts the question into a mathematical vector (embedding) using a local ONNX model
2. Searches the uploaded document chunks for the most relevant pieces using cosine similarity
3. Fetches the conversation history for the user's session (up to 5 prior Q&A turns)
4. Feeds the document chunks + conversation history + new question as a structured prompt to Google Gemini 2.5 Flash
5. Gemini generates a grounded, context-aware answer
6. The new Q&A turn is saved to the session for future requests
7. The session auto-expires after 30 minutes of inactivity (cleaned up by a background scheduler)

### Why RAG instead of just asking the LLM?

LLMs are trained on public internet data up to a cutoff date. They don't know about **your** private documents. RAG lets you inject your own knowledge into every query. The LLM's role becomes "given this context, answer this question" rather than "use everything you know".

---

## 2. Technology Stack

| Layer           | Technology                              | Version       | Purpose                             |
| --------------- | --------------------------------------- | ------------- | ----------------------------------- |
| Framework       | Spring Boot                             | 3.3.5         | Application container               |
| Language        | Java                                    | 21            | Language runtime                    |
| Build           | Maven                                   | 3.x           | Dependency management               |
| LLM             | Google Gemini 2.5 Flash (via Spring AI) | 1.1.4         | Answer generation                   |
| Embeddings      | ONNX all-MiniLM-L6-v2                   | Custom bean   | Local text → vector conversion      |
| ONNX Runtime    | Microsoft ONNX Runtime (Java)           | via Spring AI | Runs the ONNX model                 |
| Tokenizer       | DJL HuggingFace Tokenizers              | 0.32.0        | Text → token IDs                    |
| Vector Store    | Spring AI SimpleVectorStore             | 1.1.4         | In-memory cosine similarity search  |
| Document Parser | Apache Tika                             | via Spring AI | Extract text from PDF/DOCX/TXT/HTML |
| Security        | Spring Security + JJWT                  | 0.12.6        | JWT-based stateless auth            |
| Monitoring      | Spring Boot Actuator                    | 3.3.5         | Health, metrics, env, beans         |
| Port            | Tomcat embedded                         | —             | 8082                                |

---

## 3. Application Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Client (Postman / UI)                │
│  Headers: Authorization: Bearer <JWT>                        │
│           X-Session-Id: <UUID>  (optional, for memory)      │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Security Filter Chain              │
│  JwtAuthenticationFilter → validates JWT → sets context     │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────┼───────────────────┐
           ▼               ▼                   ▼
   AuthController   DocumentController    QueryController
   POST /auth/login  POST /upload          POST /query
                     GET /documents        (per-doc & global)
           │               │                   │
           ▼               ▼                   ▼
      JwtService     DocumentService       QueryService
      (sign/verify)  (Tika → ONNX →        (ONNX → VectorStore
                      VectorStore)          → ChatHistory
                                            → Gemini)
                           │                   │
                     ┌─────▼─────┐     ┌───────▼────────┐
                     │ Simple    │     │ ChatHistory     │
                     │VectorStore│     │ Service         │
                     │(in-memory)│     │(ConcurrentHashMap│
                     └───────────┘     └────────────────┘
                           │                   │
                     ┌─────▼─────┐     ┌───────▼────────┐
                     │ONNX Model │     │ Gemini 2.5 Flash│
                     │(local CPU)│     │ (Google AI API) │
                     └───────────┘     └────────────────┘
```

### Package Structure

```
com.ragapp
├── SpringRagApplication.java        — Main class, @EnableScheduling
├── auth/
│   ├── AuthController.java          — POST /auth/login
│   ├── JwtAuthenticationFilter.java — Reads Bearer token on every request
│   └── JwtService.java              — Signs/verifies JWT tokens
├── config/
│   ├── AiConfig.java                — Creates EmbeddingModel + SimpleVectorStore beans
│   ├── OnnxEmbeddingModel.java      — Custom ONNX runner (the embedding engine)
│   ├── SecurityConfig.java          — Security rules, STATELESS sessions
│   └── UserConfig.java              — In-memory users (admin, user)
├── document/
│   ├── DocumentController.java      — Upload & list endpoints
│   └── DocumentService.java         — Tika → chunk → embed → store pipeline
├── dto/
│   ├── ChatTurn.java                — NEW: record(question, answer, timestamp)
│   ├── DocumentInfo.java            — Document metadata
│   ├── DocumentUploadResponse.java  — Upload response DTO
│   ├── LoginRequest.java            — Login request DTO
│   ├── LoginResponse.java           — Login response DTO (contains token)
│   ├── QueryRequest.java            — { "question": "..." }
│   └── QueryResponse.java           — { sessionId, answer, question, documentId, chunks }
├── exception/
│   └── GlobalExceptionHandler.java  — @RestControllerAdvice, handles all errors
└── query/
    ├── ChatHistoryService.java      — NEW: in-memory session store
    ├── GlobalQueryController.java   — POST /api/query (cross-document)
    ├── QueryController.java         — POST /api/documents/{id}/query
    └── QueryService.java            — Core RAG + conversational memory logic
```

---

## 4. Document Ingestion Pipeline

**Endpoint:** `POST /api/documents/upload` (multipart/form-data, key=`file`)

### Step-by-step

```
File (PDF/DOCX/TXT/etc.)
    │
    ▼  Step 1: Parse
TikaDocumentReader
    — Apache Tika detects file type automatically (magic bytes, not extension)
    — Extracts raw text from the file
    — Returns a List<Document> (usually one Document per file)
    │
    ▼  Step 2: Chunk
TokenTextSplitter(chunkSize=800, overlap=100)
    — Splits the raw text into chunks of ~800 tokens
    — 100-token overlap between adjacent chunks (so context isn't lost at boundaries)
    — Splits at sentence boundaries where possible (.  ?  !  ;)
    — Result: List<Document> with many smaller chunks
    │
    ▼  Step 3: Tag
chunk.getMetadata().put("documentId", documentId)
    — Each chunk gets a UUID documentId stamped into its metadata
    — This enables per-document filtering during queries
    │
    ▼  Step 4: Embed
OnnxEmbeddingModel.call(chunks)
    — Each chunk's text is converted to a 384-dimension float[] vector
    — Runs locally on CPU via ONNX Runtime (no API call)
    │
    ▼  Step 5: Store
SimpleVectorStore.add(chunks)
    — Stores (vector, metadata, text) for every chunk in memory
    │
    ▼  Step 6: Track
documentStore.put(documentId, new DocumentInfo(...))
    — A ConcurrentHashMap<String, DocumentInfo> tracks all uploaded docs
    — Used for listing docs and validating documentId on query requests
```

### Why chunk documents?

- LLMs have a maximum context window. Even Gemini 2.5 Flash with 1M tokens cannot efficiently process thousands of documents at once for retrieval.
- Chunking gives fine-grained retrieval — you retrieve the 4 most relevant **paragraphs**, not the 4 most relevant **entire documents**.
- Overlap prevents breaking sentences across chunk boundaries. If a sentence starts at token 795 and the chunk ends at 800, the next chunk starts at 700, so the sentence is fully present in both.

---

## 5. ONNX Embeddings — What, Why, and How

### What is ONNX?

**ONNX (Open Neural Network Exchange)** is an open standard format for representing machine learning models. Think of it as the "PDF format" for AI models — any ML framework (PyTorch, TensorFlow, scikit-learn) can export a model to `.onnx`, and any ONNX runtime can run it regardless of the original framework.

### The model: all-MiniLM-L6-v2

- Developed by Microsoft, fine-tuned on sentence-pair tasks
- A distilled (compressed) version of BERT — 6 transformer layers instead of 12
- Input: a text string
- Output: a 384-dimensional float vector (embedding)
- The vector captures the **semantic meaning** of the text — sentences with similar meaning will have vectors pointing in similar directions in 384D space

### Why ONNX instead of calling an embedding API (like Google's text-embedding-004)?

| Aspect          | ONNX (local)                                | Embedding API (remote)        |
| --------------- | ------------------------------------------- | ----------------------------- |
| **Cost**        | Free — runs on CPU                          | Charged per token             |
| **Latency**     | ~5-50ms (local CPU)                         | ~100-500ms (network)          |
| **Privacy**     | Data never leaves your machine              | Data sent to Google/OpenAI    |
| **Offline**     | Works with no internet after first download | Requires internet always      |
| **Consistency** | Model is fixed — embeddings never change    | API may update model silently |
| **Rate limits** | None                                        | Has quota limits              |

For a demo/portfolio app, ONNX is ideal: free, private, no quota worries.

### How ONNX works internally — step by step

```
Input text: "Spring Boot simplifies Java development"
                │
                ▼
Step 1: TOKENIZATION (HuggingFaceTokenizer — DJL)
    — Text is split into "tokens" (sub-words from a 30,000-word vocabulary)
    — "Spring" → [3799]
    — "Boot" → [11733]
    — "simplifies" → [19890]
    — Special tokens added: [CLS] at start, [SEP] at end
    — Three arrays produced:
        input_ids:      [101, 3799, 11733, 19890, 1037, 2408, ...]  (token IDs)
        attention_mask: [1,   1,    1,     1,     1,    1,   ...]   (1=real, 0=padding)
        token_type_ids: [0,   0,    0,     0,     0,    0,   ...]   (0 for single sentence)
                │
                ▼
Step 2: ONNX INFERENCE (OrtSession.run)
    — The three 2D long[][] arrays are converted to OnnxTensor objects
    — Fed into the ONNX session (the loaded model.onnx file)
    — The model runs 6 transformer attention layers internally
    — Output: last_hidden_state — a 3D float array [batch][seq_len][768 or 384]
              shape for 1 sentence of 12 tokens: [1][12][384]
                │
                ▼
Step 3: MEAN POOLING (pure Java)
    — We have one 384-dim vector per token, but we want ONE vector per sentence
    — Mean pooling: for each of the 384 dimensions, average the values
      across all non-padding tokens (using attention_mask to exclude padding)
    — Result: float[384] — the sentence embedding
                │
                ▼
Step 4: L2 NORMALIZATION
    — Divide each element by the vector's magnitude so it becomes a unit vector
    — This ensures cosine similarity == dot product (simpler math, same result)
    — Result: final normalized float[384] embedding
```

### Where does the model file live?

On first startup, `ResourceCacheService` downloads:

- `tokenizer.json` (~780 KB) from Spring AI's GitHub
- `model.onnx` (~23 MB) from GitHub LFS

Cached at: `%TEMP%\spring-ai-onnx-generative\` (Windows)

On subsequent startups, no download — reads from disk. This is why the first run is slow and subsequent runs are fast.

---

## 6. Vector Similarity Search — How It Works

### The core idea

Every chunk of every uploaded document is stored as a 384-dimensional float vector. When you ask a question, your question is also converted to a 384-dim vector using the same model. Then we measure the **angle** between the question vector and every stored chunk vector. Chunks that are semantically similar to the question will have vectors pointing in nearly the same direction — small angle, high cosine similarity.

### Cosine Similarity formula

```
cosine_similarity(A, B) = (A · B) / (|A| × |B|)

Where:
  A · B = dot product = sum of (A[i] × B[i]) for each dimension
  |A|   = magnitude of A = sqrt(sum of A[i]²)

Range: -1 to +1
  +1 = identical direction (very similar meaning)
   0 = perpendicular (unrelated)
  -1 = opposite direction (opposite meaning)
```

Since our embeddings are L2-normalized (unit vectors), `|A| = |B| = 1`, so cosine similarity reduces to just the dot product.

### Example — Why this works

```
Question:  "What is Spring Boot?"
    → vector: [0.21, -0.43, 0.87, 0.12, ...]  (384 numbers)

Chunk A: "Spring Boot is a Java framework..."
    → vector: [0.23, -0.41, 0.85, 0.11, ...]
    → cosine similarity: 0.97 ← HIGH, very similar

Chunk B: "The patient's blood pressure was 120/80..."
    → vector: [-0.31, 0.72, -0.15, 0.44, ...]
    → cosine similarity: 0.12 ← LOW, unrelated
```

The ONNX model was trained to produce vectors where **semantic neighbors are geometric neighbors**. It doesn't care about exact word matches — "automobile" and "car" will be close in vector space even though they share no characters.

### How SimpleVectorStore does it

`SimpleVectorStore` is Spring AI's built-in in-memory store. On a `similaritySearch` call:

1. Embed the query (convert question to vector)
2. For every stored chunk, compute cosine similarity between query vector and chunk vector
3. Sort all chunks by similarity descending
4. Apply filter expression if present (e.g., `documentId == 'abc-123'`)
5. Return top K results (K=4 by default, configured via `app.rag.top-k`)

### Per-document vs global search

```java
// Per-document: only chunks from THIS document are candidates
String filterExpression = "documentId == '" + documentId + "'";

// Global: ALL chunks from ALL documents are candidates (no filter)
SearchRequest.builder().query(question).topK(topK).build()
```

---

## 7. RAG Query Pipeline

Full flow when `POST /api/documents/{id}/query` is called:

```
Request: { "question": "What does this document say about testing?" }
Header:  X-Session-Id: abc-123 (optional)

Step 1: Resolve session
    — If X-Session-Id present and non-blank → use it
    — If missing → generate UUID.randomUUID() → new session starts

Step 2: Vector search
    — Embed the question using ONNX → float[384]
    — Search SimpleVectorStore with filter: documentId == '{id}'
    — Retrieve top-4 most similar chunks

Step 3: Build RAG context
    — Join all retrieved chunk texts: chunk1 + "\n\n---\n\n" + chunk2 + ...
    — This becomes the {context} variable in the system prompt

Step 4: Fetch conversation history
    — chatHistoryService.getHistory(sessionId)
    — Returns ordered list of ChatTurn objects (up to 5 previous Q&A pairs)
    — Converted to Spring AI Message objects:
        UserMessage("previous question 1")
        AssistantMessage("previous answer 1")
        UserMessage("previous question 2")
        AssistantMessage("previous answer 2")
        ...

Step 5: Build and send Gemini prompt
    — System prompt: "You are a helpful assistant... Context: {context}"
    — History messages: alternating UserMessage/AssistantMessage
    — Current question: UserMessage("What does this document say about testing?")
    — ChatClient sends this full conversation to Gemini 2.5 Flash API

Step 6: Save turn
    — chatHistoryService.addTurn(sessionId, question, answer)
    — Appended to the session's Deque (oldest evicted if > 5 turns)

Step 7: Return response
    {
      "sessionId": "abc-123",
      "answer": "The document mentions testing in...",
      "question": "What does this document say about testing?",
      "documentId": "{id}",
      "relevantChunks": ["first 200 chars of chunk 1...", ...]
    }
```

---

## 8. JWT Authentication — How It Works

### What is JWT?

**JSON Web Token** — a self-contained, cryptographically signed token that carries user identity and claims. It has three parts separated by dots: `header.payload.signature`

```
eyJhbGciOiJIUzM4NCJ9     ← Base64 encoded header:  {"alg":"HS384"}
.
eyJzdWIiOiJhZG1pbiIsInJvbGVzIjpbeyJhdXRob3JpdHkiOiJST0xFX0FETUlOIn1dfQ==
                          ← Base64 encoded payload: {"sub":"admin","roles":[...],"iat":...,"exp":...}
.
<HMAC-SHA384 signature>   ← Signature = HMAC-SHA384(header + "." + payload, secretKey)
```

### Why JWT is stateless — and why it matters

**Traditional session-based auth:**

```
Client → Server: Login
Server → Database: INSERT session (sessionId=xyz, userId=1, expiresAt=...)
Server → Client: Set-Cookie: sessionId=xyz

On every request:
Client → Server: Cookie: sessionId=xyz
Server → Database: SELECT * FROM sessions WHERE sessionId=xyz  ← DB hit every time!
```

**JWT-based auth (our app):**

```
Client → Server: Login
Server: Creates JWT, signs it with secret key. NO database storage.
Server → Client: { "token": "eyJ..." }

On every request:
Client → Server: Authorization: Bearer eyJ...
Server: Verify signature with secret key (pure math, no DB hit)
       Decode payload, extract username, check expiry.
       DONE — no database, no session store.
```

**Why stateless matters:**

1. **Scalability** — you can have 100 server instances and any of them can validate any token without coordination
2. **No DB dependency** — auth works even if the DB is slow or down
3. **Microservices friendly** — any service with the same secret key can validate tokens

### How it works in our app

**Login flow:**

```java
// JwtService.generateToken()
Jwts.builder()
    .subject("admin")                     // who this token is for
    .claims(Map.of("roles", authorities)) // ROLE_ADMIN or ROLE_USER
    .issuedAt(new Date())                 // when issued
    .expiration(new Date(now + 3600000))  // expires in 1 hour
    .signWith(hmacSha384Key)              // sign with HS384
    .compact()                            // serialize to string
```

**Every request flow:**

```java
// JwtAuthenticationFilter (runs before every controller method)
String header = request.getHeader("Authorization"); // "Bearer eyJ..."
String jwt = header.substring(7);                   // strip "Bearer "
String username = jwtService.extractUsername(jwt);   // decode + verify signature
UserDetails user = userDetailsService.load(username); // load from in-memory store
if (jwtService.isTokenValid(jwt, user)) {            // check expiry + username match
    // Set Spring Security context — user is authenticated for this request
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
    );
}
```

### Algorithm: HS384 (HMAC-SHA384)

- HMAC = Hash-based Message Authentication Code
- SHA-384 = 384-bit hash function
- The server signs with the secret key. Any party with the same key can verify.
- We use HS384 (stronger than the common HS256) — 48-byte output instead of 32-byte

### Users in our app

```
admin / admin123 → ROLE_ADMIN → can access /actuator/**
user  / user123  → ROLE_USER  → cannot access /actuator/**
```

Stored in-memory via `UserConfig.java` (no database). Passwords are BCrypt hashed.

---

## 9. Conversational Memory — The New Feature

### The problem with stateless RAG

Before this feature, every query was completely independent:

```
Turn 1: "What is Spring Boot?"
        → Gemini: "Spring Boot is a Java framework..."

Turn 2: "Tell me more about its auto-configuration"
        → Gemini had NO idea what "its" referred to
        → It would either guess incorrectly or ask what you mean
```

This happens because without history, the Gemini prompt for Turn 2 is simply:

```
System: "Answer using ONLY this context: [4 document chunks]"
User:   "Tell me more about its auto-configuration"
```

Gemini has no reference for what "its" means.

### The solution: inject history into the Gemini prompt

With conversational memory, the Gemini prompt for Turn 2 becomes:

```
[System]
You are a helpful assistant... Context: {RAG chunks}

[User]        ← Turn 1 question (from stored history)
What is Spring Boot?

[Assistant]   ← Turn 1 answer (from stored history)
Spring Boot is a Java framework that simplifies...

[User]        ← CURRENT question
Tell me more about its auto-configuration
```

Now Gemini fully understands "its" — it refers to Spring Boot, established in Turn 1.

### How the three components work together

The feature involves three components:

```
┌──────────────────────────────────────────────────────────────┐
│                       HTTP Request                           │
│  X-Session-Id: abc-123  (optional header)                    │
└───────────────────┬──────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  QueryController / GlobalQueryController                     │
│  Reads X-Session-Id from @RequestHeader                      │
│  Passes it to QueryService                                   │
└───────────────────┬──────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  QueryService.buildResponse()                                │
│  1. resolveSessionId() → reuse or generate UUID             │
│  2. Do vector search → get top-4 chunks                     │
│  3. chatHistoryService.getHistory(sessionId) → List<ChatTurn>│
│  4. Convert ChatTurns → List<Message> (User + Assistant)    │
│  5. Send to Gemini: system + history + current question     │
│  6. chatHistoryService.addTurn(sessionId, Q, A)             │
│  7. Return QueryResponse (includes sessionId in body)        │
└───────────────────┬──────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  ChatHistoryService                                          │
│  ConcurrentHashMap<sessionId → SessionData>                  │
│  SessionData = { Deque<ChatTurn>, AtomicLong lastActiveMs }  │
│  @Scheduled cleanup every 5 minutes                         │
└──────────────────────────────────────────────────────────────┘
```

### Key design decision: server-side history storage

We store history on the **server**, not the client. The client only sends a short UUID string as `X-Session-Id`. The server does all the heavy lifting.

**Why server-side?**

1. **Payload size** — if the client had to re-send 5 Q&A turns in every request body, the request would be huge. With server-side storage, the client sends 36 characters (a UUID) and gets back the full context.

2. **Security** — the server controls the window size (max 5 turns) and TTL (30 minutes). The client cannot inject fake history or bypass limits.

3. **Simplicity** — the client doesn't need to track or manage history at all. It just saves and re-sends the session ID.

4. **Natural expiry** — the server automatically purges idle sessions. If the user abandons a conversation, the memory is reclaimed after 30 minutes.

### The new HTTP header: X-Session-Id

We chose a **custom request header** (`X-Session-Id`) rather than a cookie or a body field:

- **Not a cookie**: cookies are browser-specific, automatically attached to requests, and have CSRF implications. Our API is meant for any client (mobile, Postman, other services) — headers are universal.
- **Not in the request body**: the body schema is `{"question": "..."}`. We don't want to mix authentication/session concerns into the business payload.
- **Custom header with `X-` prefix**: the `X-` prefix signals it's a custom, application-defined header, not an HTTP standard header. Clear and conventional.

### The `required = false` annotation

```java
@RequestHeader(value = "X-Session-Id", required = false) String sessionId
```

`required = false` means the header is optional. If the client doesn't send it, Spring sets `sessionId` to `null`. The `resolveSessionId()` method in `QueryService` handles this:

```java
private String resolveSessionId(String sessionId) {
    return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
}
```

- If `sessionId` is `null` (header absent) → generate a new UUID
- If `sessionId` is blank (client sent empty string) → generate a new UUID
- Otherwise → use the client's session ID

This means **every request gets a session** — first-time callers just start with an empty history automatically.

---

## 10. ChatHistoryService — Complete Code Walkthrough

This is the most important new class. Let's go line by line.

### The complete source code

```java
@Service
public class ChatHistoryService {

    @Value("${app.rag.chat-history.max-turns:5}")
    private int maxTurns;

    @Value("${app.rag.chat-history.ttl-minutes:30}")
    private int ttlMinutes;

    private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}

    private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>();

    public List<ChatTurn> getHistory(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data == null) {
            return List.of();
        }
        data.lastActiveMs().set(System.currentTimeMillis());
        synchronized (data.turns()) {
            return new ArrayList<>(data.turns());
        }
    }

    public void addTurn(String sessionId, String question, String answer) {
        SessionData data = sessions.computeIfAbsent(sessionId,
                id -> new SessionData(new ArrayDeque<>(), new AtomicLong(System.currentTimeMillis())));
        data.lastActiveMs().set(System.currentTimeMillis());
        synchronized (data.turns()) {
            if (data.turns().size() >= maxTurns) {
                data.turns().pollFirst();
            }
            data.turns().addLast(new ChatTurn(question, answer, LocalDateTime.now()));
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void evictExpiredSessions() {
        long cutoffMs = System.currentTimeMillis() - (ttlMinutes * 60_000L);
        sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs().get() < cutoffMs);
    }
}
```

### Line-by-line explanation

**`@Service`**
Marks this as a Spring-managed bean. Spring creates exactly ONE instance (singleton) at startup and injects it into every class that needs it. Since there's one instance shared across all threads, thread safety is critical.

**`@Value("${app.rag.chat-history.max-turns:5}")`**
Reads the `app.rag.chat-history.max-turns` property from `application.properties`. The `:5` is a default value — if the property isn't defined, it uses 5. This makes the window size configurable without recompiling.

**`private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}`**
A private inner record. Records are Java 16+ immutable data carriers. This `SessionData` holds:

- `turns`: the ordered history of Q&A pairs (the Deque)
- `lastActiveMs`: the millisecond timestamp of last activity (for TTL)

"Immutable" here means the `turns` and `lastActiveMs` references themselves are final — you can't point them at different objects. But the Deque and AtomicLong's **contents** can still change, which is why we need synchronization.

**`private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>()`**
The master map. `String` key = session UUID. `SessionData` value = the turns + last-active timestamp. `final` means the reference to the map never changes after construction (the map itself grows/shrinks as sessions are added/removed).

**`getHistory(String sessionId)`**

```java
SessionData data = sessions.get(sessionId);
if (data == null) {
    return List.of();  // session doesn't exist yet → empty history
}
```

`List.of()` returns an immutable empty list. Efficient — no allocation overhead.

```java
data.lastActiveMs().set(System.currentTimeMillis());
```

Update the "last seen" timestamp. This prevents the session from being expired by the cleanup job as long as the user is actively making queries.

```java
synchronized (data.turns()) {
    return new ArrayList<>(data.turns());
}
```

`synchronized (data.turns())` locks on the `ArrayDeque` object itself. Inside, we create a **snapshot** — a brand new `ArrayList` that is a copy of the current Deque contents. We return the copy, not the Deque itself. Why a copy? Because the Deque is live and mutable — if we returned a reference to the Deque directly, the caller could iterate it while another thread modifies it (causing `ConcurrentModificationException`). The copy is safe to read outside the lock.

**`addTurn(String sessionId, String question, String answer)`**

```java
SessionData data = sessions.computeIfAbsent(sessionId,
        id -> new SessionData(new ArrayDeque<>(), new AtomicLong(System.currentTimeMillis())));
```

`computeIfAbsent` is an atomic operation on `ConcurrentHashMap`. It checks if `sessionId` exists:

- If YES → returns the existing `SessionData` (no change)
- If NO → calls the lambda to create a new `SessionData(new ArrayDeque<>(), new AtomicLong(now))`, stores it, and returns it

The atomicity guarantee means that even if two threads call `addTurn` simultaneously for the same **new** session ID, exactly one `SessionData` is created. There is no race condition where both threads create a `SessionData` and one silently overwrites the other.

```java
data.lastActiveMs().set(System.currentTimeMillis());
```

Update last-active again. `AtomicLong.set()` is a single atomic write — no synchronization needed.

```java
synchronized (data.turns()) {
    if (data.turns().size() >= maxTurns) {
        data.turns().pollFirst();   // evict oldest turn (from the HEAD)
    }
    data.turns().addLast(new ChatTurn(question, answer, LocalDateTime.now()));
}
```

Inside the lock:

1. Check if we've hit the limit (maxTurns = 5 by default)
2. If yes, `pollFirst()` removes and discards the oldest turn from the front
3. `addLast()` appends the new turn at the back

After this, the Deque always has at most `maxTurns` entries.

**`evictExpiredSessions()`**

```java
@Scheduled(fixedRate = 300_000)
public void evictExpiredSessions() {
    long cutoffMs = System.currentTimeMillis() - (ttlMinutes * 60_000L);
    sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs().get() < cutoffMs);
}
```

Runs every 300,000 ms = 5 minutes. `cutoffMs` is the boundary: any session whose last active time is before this is stale. `removeIf` on `ConcurrentHashMap.entrySet()` is thread-safe — it atomically removes all matching entries.

---

## 11. QueryService — How Conversational Memory Is Wired In

The `QueryService` was the biggest modified class. Here is the relevant section that handles conversational memory:

### Constructor change

```java
// BEFORE (base RAG app):
public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel,
                    DocumentService documentService) {
    this.vectorStore = vectorStore;
    this.chatClient = ChatClient.builder(chatModel).build();
    this.documentService = documentService;
}

// AFTER (with conversational memory):
public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel,
                    DocumentService documentService, ChatHistoryService chatHistoryService) {
    this.vectorStore = vectorStore;
    this.chatClient = ChatClient.builder(chatModel).build();
    this.documentService = documentService;
    this.chatHistoryService = chatHistoryService;  // ← NEW injection
}
```

Spring auto-wires `ChatHistoryService` because it's a `@Service` bean.

### Method signature change

```java
// BEFORE:
public QueryResponse query(String documentId, QueryRequest request) { ... }
public QueryResponse queryAllDocuments(QueryRequest request) { ... }

// AFTER:
public QueryResponse query(String documentId, QueryRequest request, String sessionId) { ... }
public QueryResponse queryAllDocuments(QueryRequest request, String sessionId) { ... }
```

Both methods now accept an optional `sessionId` (can be null).

### The `resolveSessionId` helper

```java
private String resolveSessionId(String sessionId) {
    return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
}
```

This method ensures every query has a session ID — either reused from the client or freshly generated. It's a private helper, not part of the public API.

### The `buildResponse` method — the core of the feature

```java
private QueryResponse buildResponse(String scope, String sessionId, String question, List<Document> relevantDocs) {

    // 1. Build RAG context string from retrieved chunks
    String context = relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));

    // 2. Fetch conversation history from ChatHistoryService
    List<ChatTurn> history = chatHistoryService.getHistory(sessionId);

    // 3. Convert ChatTurn records to Spring AI Message objects
    List<Message> historyMessages = new ArrayList<>();
    for (ChatTurn turn : history) {
        historyMessages.add(new UserMessage(turn.question()));      // prior question
        historyMessages.add(new AssistantMessage(turn.answer()));   // prior answer
    }

    // 4. Build and send the complete Gemini prompt
    String answer = chatClient.prompt()
            .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
            .messages(historyMessages)   // ← history injected here
            .user(question)
            .call()
            .content();

    // 5. Save this Q&A turn to the session
    chatHistoryService.addTurn(sessionId, question, answer);

    // 6. Build response (truncate chunks to 200 chars for readability)
    List<String> chunks = relevantDocs.stream()
            .map(doc -> {
                String content = doc.getText();
                return content.substring(0, Math.min(content.length(), 200)) + "...";
            })
            .toList();

    return new QueryResponse(sessionId, answer, question, scope, chunks);
}
```

**Step 3 in detail — converting ChatTurn to Message:**

Spring AI's `ChatClient` API accepts a `List<Message>`. There are two message types we use:

- `UserMessage(String text)` — represents a message sent by the user
- `AssistantMessage(String text)` — represents a message sent by the AI/assistant

The Gemini API requires messages to **alternate strictly**: User, Assistant, User, Assistant, ...

If we have 2 prior turns:

```
history = [
  ChatTurn(Q1, A1, timestamp),
  ChatTurn(Q2, A2, timestamp)
]

historyMessages becomes:
  [UserMessage(Q1), AssistantMessage(A1), UserMessage(Q2), AssistantMessage(A2)]
```

Then `.user(currentQuestion)` adds the current User message at the end, so the final sequence is:

```
UserMessage(Q1) → AssistantMessage(A1) → UserMessage(Q2) → AssistantMessage(A2) → UserMessage(currentQuestion)
```

This is exactly what the Gemini API expects for multi-turn conversations.

**Step 4 — the ChatClient prompt chain:**

```java
chatClient.prompt()
    .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
    // ↑ Sets the system instruction with the RAG context filled in
    .messages(historyMessages)
    // ↑ Injects all prior turns as multi-turn conversation history
    .user(question)
    // ↑ Appends the current question as the latest User message
    .call()
    // ↑ Makes the HTTPS request to the Gemini API
    .content();
    // ↑ Extracts the response text from the Gemini JSON response
```

**Why do we save the turn AFTER calling Gemini (not before)?**

Because we need Gemini's answer to create the `ChatTurn`. The turn is `(question, answer, timestamp)` — we can't store it until we have the answer. If Gemini throws an exception (e.g., 429 rate limit), `chatHistoryService.addTurn` is never called, so the failed turn is not saved to history. This is correct behavior — the user can retry without a corrupted history entry.

---

## 12. Java Records — How ChatTurn Works Internally

```java
public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}
```

`record` is a Java 16+ feature. This single line generates:

1. **Three private final fields**: `question`, `answer`, `timestamp`
2. **A canonical constructor**: `ChatTurn(String question, String answer, LocalDateTime timestamp)`
3. **Three getter methods** (named after the fields, not `get...`): `question()`, `answer()`, `timestamp()`
4. **`equals()`**: two `ChatTurn` objects are equal if all three fields are equal
5. **`hashCode()`**: consistent with `equals()`
6. **`toString()`**: `ChatTurn[question=..., answer=..., timestamp=...]`

Records are **immutable** — the fields are final. You cannot do `turn.question = "..."`. You can only read values.

**Why a record for ChatTurn?**

- A Q&A turn is a **pure data carrier** — it has no behavior, no state changes, just data
- Records prevent accidental mutation (no setters)
- The auto-generated `equals`/`hashCode`/`toString` are correct by default
- The code is concise (one line vs. 30+ lines for an equivalent class)

**How it's used:**

```java
// Creation (in ChatHistoryService.addTurn):
data.turns().addLast(new ChatTurn(question, answer, LocalDateTime.now()));

// Reading (in QueryService.buildResponse):
for (ChatTurn turn : history) {
    historyMessages.add(new UserMessage(turn.question()));    // turn.question() — getter
    historyMessages.add(new AssistantMessage(turn.answer())); // turn.answer() — getter
}
```

`LocalDateTime.now()` captures the current system time at the moment the turn is recorded. This is used for auditing — you can see exactly when each turn happened.

---

## 13. ConcurrentHashMap vs HashMap — Deep Dive

### Why not just use `HashMap`?

Our `ChatHistoryService` is a **Spring `@Service` singleton** — one instance shared across all threads. Tomcat uses a thread pool: each HTTP request runs on a separate thread. If 10 users are querying simultaneously, there are 10 threads, potentially all hitting `ChatHistoryService` at the same time.

`HashMap` was designed for single-threaded use. Under concurrent access:

**Problem 1: Internal state corruption during resize**

`HashMap` uses an array of "buckets". When the number of entries exceeds the load factor threshold, it creates a new, larger array and re-distributes all entries (rehashing). If two threads trigger a resize simultaneously, the internal linked lists in each bucket can become corrupted or circular. A circular linked list causes `get()` to loop forever (an infinite loop with no exit condition).

**Problem 2: Lost updates**

Two threads both try to `put(sameKey, value)` at the same time. They both read the current state, both compute the new state, and both write back. One write silently overwrites the other. One session's turn is lost.

**Problem 3: Stale reads**

Without proper memory visibility guarantees (`volatile` or `synchronized`), a value written by Thread A may not be visible to Thread B due to CPU caching. Thread B sees the old value.

### How `ConcurrentHashMap` solves this

`ConcurrentHashMap` uses **lock striping**. In Java 8+, it uses CAS (compare-and-swap) operations at the bucket level and only falls back to locking when there's a genuine conflict.

```
Internal structure (simplified):
  Bucket 0:  [entry] → [entry]   (lock: bucket's own node lock)
  Bucket 1:  [entry]             (lock: bucket's own node lock)
  Bucket 2:  empty
  ...
  Bucket N:  [entry] → [entry]   (lock: bucket's own node lock)
```

- `get()` is **completely lock-free** — reads use volatile/CAS, no locking at all
- `put()` only locks **one bucket** — other threads can concurrently put into different buckets
- Resize is handled with a "forwarding node" mechanism — reads still work during resize
- `computeIfAbsent()` is atomic — guaranteed one-time creation even under concurrent access

**Practical result:** In a 16-bucket ConcurrentHashMap, 16 puts can happen simultaneously (one per bucket) with zero blocking. For 1,000 different session IDs, the probability that two concurrent operations land in the same bucket is very low — effectively concurrent.

### Why not `Hashtable` or `Collections.synchronizedMap(HashMap)`?

Both use **one lock for the entire map**:

```java
// synchronized HashMap — every operation locks THE ENTIRE MAP
public synchronized V put(K key, V value) { ... }
public synchronized V get(Object key) { ... }
```

Thread 1 doing `get("session-A")` blocks Thread 2 from doing `get("session-B")` — even though they access completely different sessions. Under high concurrency, all threads queue up to take the single lock, creating a bottleneck.

`ConcurrentHashMap`'s bucket-level locking means Thread 1 and Thread 2 can proceed in parallel as long as their keys hash to different buckets (which is the case for different UUIDs with very high probability).

---

## 14. Why Deque? How It Stores Q&A

### What is a Deque?

`Deque` = **Double-Ended Queue** — a data structure that supports efficient add/remove from both ends:

| Operation     | End              | Time |
| ------------- | ---------------- | ---- |
| `addLast(x)`  | Tail             | O(1) |
| `addFirst(x)` | Head             | O(1) |
| `pollFirst()` | Head (removes)   | O(1) |
| `pollLast()`  | Tail (removes)   | O(1) |
| `peekFirst()` | Head (read only) | O(1) |
| Iteration     | Head → Tail      | O(n) |

We use `ArrayDeque` (backed by a circular array — resizes automatically when needed).

### Why Deque for a sliding window? Why not List or Queue?

We need **FIFO (First In, First Out) with bounded size** — oldest entry gets dropped when full.

With a `List<ChatTurn>`:

- Adding is easy (`list.add(turn)`)
- Removing oldest requires `list.remove(0)` → **O(n)** because all elements shift left

With a `Deque<ChatTurn>`:

- Adding newest: `deque.addLast(turn)` → **O(1)**
- Removing oldest: `deque.pollFirst()` → **O(1)**

For a 5-turn window, the difference seems trivial. But conceptually, the Deque is the semantically correct choice — it is literally designed for this pattern.

### The sliding window in action

```
Initial state (empty new session):
  HEAD → [] ← TAIL

After Turn 1 (Q1: "What is RAG?"):
  HEAD → [Q1/A1] ← TAIL

After Turn 2 (Q2: "How does ONNX work?"):
  HEAD → [Q1/A1] [Q2/A2] ← TAIL

After Turn 3:
  HEAD → [Q1/A1] [Q2/A2] [Q3/A3] ← TAIL

After Turn 4:
  HEAD → [Q1/A1] [Q2/A2] [Q3/A3] [Q4/A4] ← TAIL

After Turn 5 (window now full, maxTurns=5):
  HEAD → [Q1/A1] [Q2/A2] [Q3/A3] [Q4/A4] [Q5/A5] ← TAIL

Turn 6 arrives:
  size(5) >= maxTurns(5) → pollFirst() removes Q1/A1
  addLast(Q6/A6)
  HEAD → [Q2/A2] [Q3/A3] [Q4/A4] [Q5/A5] [Q6/A6] ← TAIL
  ↑ Q1/A1 is gone forever. The window slides forward.

Turn 7:
  pollFirst() removes Q2/A2
  HEAD → [Q3/A3] [Q4/A4] [Q5/A5] [Q6/A6] [Q7/A7] ← TAIL
```

When we iterate this Deque and send to Gemini, the order is always HEAD to TAIL = **oldest to newest**, which is the correct chronological order for a conversation.

### What is stored in each ChatTurn?

```java
public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}
```

- `question` — the exact text of the user's question
- `answer` — the exact text of Gemini's response
- `timestamp` — `LocalDateTime.now()` at the time the turn was saved

These are stored as plain strings. No compression, no truncation — the full text. This means a session with 5 very long Q&A turns could take significant memory (in the worst case, hundreds of KB per session). For a development/portfolio app this is fine; production systems would set lower limits or use a persistent store.

---

## 15. Why synchronized on the Deque?

This is the most subtle concurrency point in the application and a common interview topic.

### The misconception

"I'm using `ConcurrentHashMap` — so everything is thread-safe, right?"

**Wrong.** `ConcurrentHashMap` only makes the **map operations** (`get`, `put`, `remove`, `computeIfAbsent`) thread-safe. It says nothing about the safety of the **values stored in the map**.

The `SessionData` inside each map value contains an `ArrayDeque`. `ArrayDeque` is **explicitly documented as not thread-safe**. Its internal array can be corrupted if two threads access it simultaneously.

### The exact scenario that would go wrong without synchronization

```
Time     Thread A (Request 1: reading history)         Thread B (Request 2: adding turn)
────────────────────────────────────────────────────────────────────────────────────────
T1       getHistory("abc"):
         data = sessions.get("abc") → SessionData
         for (ChatTurn t : data.turns()) {            addTurn("abc", Q, A):
         ↑ starts iterating ArrayDeque                data = sessions.get("abc") → same SessionData
T2       [iterating turn 0...]                        data.turns().addLast(newTurn)
                                                      ↑ MODIFIES ArrayDeque DURING ITERATION
T3       [iterating turn 1...]
         ↑ ArrayDeque's internal modCount changed!
         → ConcurrentModificationException!
```

`ArrayDeque` (and most Java collections) maintain a modification counter (`modCount`). When iteration starts, it records the current `modCount`. On every `next()` call, it checks that `modCount` hasn't changed. If another thread modified the Deque during iteration, `modCount` changes → `ConcurrentModificationException` is thrown.

### How `synchronized (data.turns())` fixes this

```java
// Thread A: getHistory
synchronized (data.turns()) {
    return new ArrayList<>(data.turns());  // copy while locked
}

// Thread B: addTurn
synchronized (data.turns()) {
    data.turns().addLast(...);  // modify while locked
}
```

`synchronized (data.turns())` uses the **Deque object itself as a monitor lock** (a.k.a. intrinsic lock). The JVM guarantees:

> At most one thread can hold a given object's monitor at any time.

So if Thread A is inside its `synchronized (data.turns())` block, Thread B's `synchronized (data.turns())` block **waits** — it cannot enter until Thread A exits. This ensures reads and writes to the same Deque never overlap.

**Why `data.turns()` is a valid lock object:**

The `SessionData` record's `turns()` method always returns the **same** `ArrayDeque` instance. `synchronized` uses object identity (memory address) for locking — as long as all threads lock on the exact same object, mutual exclusion is guaranteed. Since `SessionData` is immutable (its `turns` reference is final), `data.turns()` always returns the same `ArrayDeque`.

### Why is `lastActiveMs` an `AtomicLong` instead of `synchronized`?

Because `lastActiveMs` only needs **single-variable atomic reads and writes**:

```java
data.lastActiveMs().set(System.currentTimeMillis());  // atomic write
data.lastActiveMs().get();                             // atomic read
```

`AtomicLong` uses CPU-level compare-and-swap (CAS) instructions, which are:

1. **Lock-free** — no thread ever waits; CAS retries on contention but never blocks
2. **Faster** than `synchronized` for single-variable operations
3. **Sufficient** — we don't need a compound check-then-act operation on `lastActiveMs`

If we used `synchronized` for `lastActiveMs`, we'd need a dedicated lock object for it (or synchronize on the Deque, mixing concerns). `AtomicLong` is cleaner and faster.

---

## 16. computeIfAbsent — Thread Safety Guarantee

```java
SessionData data = sessions.computeIfAbsent(sessionId,
        id -> new SessionData(new ArrayDeque<>(), new AtomicLong(System.currentTimeMillis())));
```

### What it does

`computeIfAbsent(key, mappingFunction)`:

1. Look up `key` in the map
2. If present → return existing value (lambda never called)
3. If absent → call lambda to compute the value → store it → return it

### Why this is important for concurrency

Consider two threads simultaneously calling `addTurn` for the **same new session ID** for the first time (e.g., two HTTP requests arrive in the same millisecond with no `X-Session-Id` header and the server generates the same... wait, UUIDs are essentially unique. But consider a client sending the same `X-Session-Id` in two simultaneous requests):

```
Thread A: sessions.computeIfAbsent("abc", id -> new SessionData(...))
Thread B: sessions.computeIfAbsent("abc", id -> new SessionData(...))
```

**Without atomicity (hypothetical broken code using get/put):**

```java
// WRONG — race condition:
if (!sessions.containsKey(sessionId)) {
    sessions.put(sessionId, new SessionData(...));
}
SessionData data = sessions.get(sessionId);
```

Thread A reads `containsKey` → false. Thread B reads `containsKey` → false. Thread A creates and puts SessionData1. Thread B creates and puts SessionData2 (overwriting SessionData1). Thread A then calls `data.turns()` on SessionData1, but the map now has SessionData2. The turns Thread A appends go to a `SessionData` that is no longer referenced by the map — they're lost.

**With `computeIfAbsent`:**

`ConcurrentHashMap.computeIfAbsent` is guaranteed to execute the lambda **at most once per key** even under concurrent access. If Thread A and Thread B race, one of them wins and creates the `SessionData`. The other thread simply gets the value the winner created. The lambda is never called twice for the same key.

---

## 17. Session ID — Generation, Lifecycle, Expiry

### Generation

```java
private String resolveSessionId(String sessionId) {
    return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
}
```

- If the client sends `X-Session-Id: abc-123` → use `abc-123`
- If missing or blank → generate `UUID.randomUUID()` → e.g., `"3f2a1b8c-4d5e-6f7a-8b9c-0d1e2f3a4b5c"`

**UUID v4** is 128 bits of randomness (122 bits effective, 6 bits are version/variant markers). There are $2^{122} \approx 5.3 \times 10^{36}$ possible UUIDs. If you generated 1 billion UUIDs per second for the age of the universe, the probability of a collision would still be negligibly small.

### Full lifecycle

```
First request (no X-Session-Id sent):
─────────────────────────────────────────────────────────
resolveSessionId(null) → UUID.randomUUID() → "sess-abc"
getHistory("sess-abc") → sessions.get("sess-abc") = null → List.of()
  (no history → no history messages sent to Gemini)
Gemini answers first question
addTurn("sess-abc", Q1, A1):
  computeIfAbsent("sess-abc") creates new SessionData(ArrayDeque, AtomicLong(now))
  Deque: [ChatTurn(Q1, A1, now)]
Response: { "sessionId": "sess-abc", "answer": "..." }
                │
                ▼ (Client saves "sess-abc", sends it next time)

Second request (X-Session-Id: sess-abc):
─────────────────────────────────────────────────────────
resolveSessionId("sess-abc") → "sess-abc" (reused as-is)
getHistory("sess-abc") → sessions.get("sess-abc") = SessionData found!
  lastActiveMs updated to now
  returns snapshot of Deque: [ChatTurn(Q1, A1, ...)]
historyMessages = [UserMessage(Q1), AssistantMessage(A1)]
Gemini receives: system + Q1/A1 history + Q2 (current question)
Gemini answers Q2 with context of Q1/A1
addTurn("sess-abc", Q2, A2):
  Deque: [ChatTurn(Q1, A1), ChatTurn(Q2, A2)]
Response: { "sessionId": "sess-abc", "answer": "..." (knows about Q1)" }
```

### The `SessionData` internal record

```java
private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}
```

- `turns` → an `ArrayDeque<ChatTurn>` — the Q&A history, max 5 entries
- `lastActiveMs` → an `AtomicLong` — the epoch millisecond of last `getHistory` or `addTurn` call

Both fields are declared in the record and are final references (you can't change which `ArrayDeque` or which `AtomicLong` they point to). The contents of the `ArrayDeque` and the value inside the `AtomicLong` do change — that's the mutable state.

---

## 18. How Gemini Receives the Full Conversation

When `chatClient.prompt()...call()` executes, Spring AI serializes the prompt into the Google Generative AI API format and sends an HTTPS POST.

### The Java prompt chain

```java
chatClient.prompt()
    .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
    .messages(historyMessages)
    .user(question)
    .call()
    .content()
```

### What gets sent over the wire (JSON)

For a conversation with 1 prior turn ("What is RAG?" / "RAG stands for...") and current question "How does it differ from keyword search?":

```json
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=...
Content-Type: application/json

{
  "system_instruction": {
    "parts": [{
      "text": "You are a helpful assistant that answers questions based on the provided context.\nUse ONLY the information from the context below...\n\nContext:\n[chunk1 text]\n\n---\n\n[chunk2 text]\n\n---\n\n[chunk3 text]\n\n---\n\n[chunk4 text]"
    }]
  },
  "contents": [
    {
      "role": "user",
      "parts": [{ "text": "What is RAG?" }]
    },
    {
      "role": "model",
      "parts": [{ "text": "RAG stands for Retrieval Augmented Generation. It is a technique that..." }]
    },
    {
      "role": "user",
      "parts": [{ "text": "How does it differ from keyword search?" }]
    }
  ],
  "generationConfig": {
    "temperature": 0.2
  }
}
```

**Note:** The Google API calls the AI role `"model"` (not `"assistant"`). Spring AI's `AssistantMessage` is automatically serialized with `"role": "model"` when using the Google GenAI adapter.

### What Gemini returns

```json
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "Unlike keyword search which looks for exact word matches, RAG uses semantic vector similarity. So even if your document says 'notice period' and you ask about 'how long before leaving', the vectors will be similar because the meaning is the same..."
          }
        ],
        "role": "model"
      },
      "finishReason": "STOP"
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 2847,
    "candidatesTokenCount": 156,
    "totalTokenCount": 3003
  }
}
```

`chatClient.call().content()` extracts `candidates[0].content.parts[0].text` — the answer string.

---

## 19. Why 5 Turns? Why Not Unlimited?

### Token budget math

Each Gemini API call has:

- A **system prompt** with RAG context chunks: ~2,000-4,000 tokens (4 chunks × 800 tokens each)
- The **current question**: ~10-50 tokens
- **History (5 turns)**: ~5 questions + 5 answers ≈ 1,000-2,000 tokens
- **Total input**: ~3,000-6,000 tokens per request

Gemini 2.5 Flash supports **1,000,000 input tokens** — so even 100 turns would be technically fine.

**But there are other reasons to cap it:**

1. **Memory leak prevention** — without a cap, a single session with thousands of turns would consume unbounded JVM heap space. Sessions are in-memory, not on disk.
2. **Cost** — each additional turn adds tokens = slightly more API cost
3. **Relevance decay** — conversations typically drift. A question asked 20 turns ago is rarely relevant to the current question. 5 turns captures the most recent context window which is almost always enough.
4. **Predictability** — bounded behavior is easier to reason about and test

**Why 5 specifically?**
5 is a good balance — enough for:

- "Tell me more about that" (1 turn back)
- "Compare what you said earlier about X vs Y" (2-3 turns back)
- Multi-step explanations (4-5 turns)

Configurable via `app.rag.chat-history.max-turns=5` in `application.properties` — you can change it without recompiling.

---

## 20. What Happens When a Session Expires?

### TTL cleanup mechanism

```java
@Scheduled(fixedRate = 300_000)  // runs every 5 minutes (300,000 ms)
public void evictExpiredSessions() {
    long cutoffMs = System.currentTimeMillis() - (ttlMinutes * 60_000L);
    // cutoffMs = now - 30 minutes
    sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs().get() < cutoffMs);
}
```

`removeIf` is an atomic operation on `ConcurrentHashMap` — it's safe to call while other threads are using the map.

### What happens when the client sends an expired sessionId?

```java
public List<ChatTurn> getHistory(String sessionId) {
    SessionData data = sessions.get(sessionId);
    if (data == null) {
        return List.of();  // empty — session not found
    }
    ...
}
```

The expired session was removed from the map. `sessions.get(oldId)` returns `null`. `getHistory` returns an empty list. The query proceeds with **no history** — as if it's a brand new session. The client gets back the same `sessionId` it sent (we don't generate a new one), so it can continue — the history just restarts from that point.

### Why every 5 minutes?

With a 30-minute TTL and a 5-minute cleanup interval, a session's **maximum actual lifetime** after last use is 35 minutes (TTL + one cleanup interval). This small overshoot is completely acceptable.

Shorter intervals (e.g., every 10 seconds) would waste CPU. Longer intervals (e.g., every hour) would let many expired sessions accumulate. 5 minutes is a practical sweet spot.

---

## 21. Spring @Scheduled — How TTL Cleanup Works

### Two annotations working together

**`@EnableScheduling` on the main class:**

```java
@SpringBootApplication(exclude = {TransformersEmbeddingModelAutoConfiguration.class})
@EnableScheduling   // ← added in the conversational memory feature
public class SpringRagApplication { ... }
```

`@EnableScheduling` tells Spring: "scan all beans for `@Scheduled` methods and register them with a background executor". Without this annotation, `@Scheduled` methods are completely ignored — they're never called.

**`@Scheduled(fixedRate = 300_000)` on the cleanup method:**

```java
@Scheduled(fixedRate = 300_000)
public void evictExpiredSessions() { ... }
```

`fixedRate = 300_000` means: **run every 300,000 milliseconds regardless of how long the previous execution took**. If the cleanup takes 50ms, the next run starts at T+300,000ms (not T+300,050ms). This is "wall-clock rate" scheduling.

Alternative: `fixedDelay = 300_000` would mean "wait 300,000ms after the previous execution finishes". For a cleanup job, `fixedRate` is the more appropriate choice — we want it to run at regular intervals.

### The cleanup logic

```java
long cutoffMs = System.currentTimeMillis() - (ttlMinutes * 60_000L);
```

If `ttlMinutes = 30`:

```
cutoffMs = now - (30 × 60,000) = now - 1,800,000 ms
```

Any session whose `lastActiveMs < cutoffMs` hasn't been used in over 30 minutes → expired.

```java
sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs().get() < cutoffMs);
```

`ConcurrentHashMap.entrySet().removeIf()` is thread-safe. The lambda is called for each entry. If it returns `true`, the entry is atomically removed. Other threads can safely `get`, `put`, and `computeIfAbsent` on the map during this operation.

### Which thread runs `@Scheduled`?

Spring creates a **background thread** (from a `TaskScheduler` thread pool) to run `@Scheduled` methods. This is completely separate from Tomcat's HTTP request threads. The cleanup runs in the background without affecting request-handling performance.

---

## 22. Spring Security Filter Chain

Every HTTP request goes through this chain before reaching any controller:

```
HTTP Request
    │
    ▼
JwtAuthenticationFilter (extends OncePerRequestFilter)
    — Runs ONCE per request, guaranteed by Spring
    — Reads "Authorization: Bearer <token>" header
    — If no header: passes through to next filter (anonymous request)
    — If header present:
        → Extract JWT
        → Verify HMAC signature (if tampered → exception → 401)
        → Check expiry
        → Load user from in-memory UserDetailsService
        → Set SecurityContextHolder (marks user as authenticated)
    │
    ▼
Spring Security Authorization Filter
    — Checks SecurityContextHolder for authenticated user
    — Applies rules from SecurityConfig:
        /auth/**          → permitAll  (no token needed)
        /actuator/health  → permitAll
        /actuator/info    → permitAll
        /actuator/**      → hasRole("ADMIN")
        anyRequest        → authenticated
    — Returns 403 if insufficient role, 401 if not authenticated
    │
    ▼
DispatcherServlet → Controller method
```

### CSRF disabled — why?

CSRF (Cross-Site Request Forgery) attacks target **cookie-based sessions**. The attacker tricks a browser into making a request that sends the victim's session cookie. Our app uses **Bearer tokens in Authorization header** — browsers don't automatically attach headers from other origins. Therefore CSRF protection is unnecessary and disabled.

### STATELESS session management

```java
.sessionManagement(session ->
    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
)
```

Spring Security won't create an `HttpSession`. No server-side session store. The `SecurityContextHolder` is populated fresh on every request from the JWT. This is the standard pattern for REST APIs.

---

## 23. Exception Handling Strategy

`GlobalExceptionHandler` (`@RestControllerAdvice`) intercepts all exceptions thrown from controllers and services, converting them to structured JSON responses.

### Handler map

| Exception                                 | HTTP Status | Scenario                                                     |
| ----------------------------------------- | ----------- | ------------------------------------------------------------ |
| `BadCredentialsException`                 | 401         | Wrong username/password at login                             |
| `IllegalArgumentException`                | 404         | Document ID not found                                        |
| `MethodArgumentNotValidException`         | 400         | `@Valid` validation failed (e.g., empty question)            |
| `MaxUploadSizeExceededException`          | 413         | File > 10MB                                                  |
| `HttpMediaTypeNotSupportedException`      | 415         | Wrong Content-Type on upload (should be multipart/form-data) |
| `MultipartException`                      | 400         | Malformed multipart request                                  |
| `MissingServletRequestParameterException` | 400         | Missing required form field                                  |
| `ClientException` (Gemini)                | 429 or 502  | Gemini rate limit (429) or API rejection (502)               |
| `Exception` (catch-all)                   | 500         | Walks cause chain looking for wrapped `ClientException`      |

### The cause-chain walking trick

Spring AI wraps `ClientException` inside `RuntimeException("Failed to generate content")`. Without this trick, a Gemini 429 would appear as a generic 500:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<...> handleGeneral(Exception ex) {
    Throwable cause = ex.getCause();
    while (cause != null) {
        if (cause instanceof ClientException clientEx) {
            return handleGeminiClientError(clientEx); // proper 429/502
        }
        cause = cause.getCause();
    }
    // Not a Gemini error — log and return 500
    log.error("Unhandled exception [{}]: {}", ex.getClass().getName(), ex.getMessage(), ex);
    return ResponseEntity.status(500).body(Map.of("error", "unexpected error", ...));
}
```

---

## 24. Actuator — Monitoring & Observability

Spring Boot Actuator exposes management endpoints.

### Access control

```
/actuator/health → PUBLIC (no token)
/actuator/info   → PUBLIC (no token)
/actuator/**     → ADMIN only (ROLE_ADMIN required)
```

### Exposed endpoints

| Endpoint                            | What it shows                                                              |
| ----------------------------------- | -------------------------------------------------------------------------- |
| `/actuator/health`                  | UP/DOWN + disk space + ping                                                |
| `/actuator/info`                    | App name, description, version from `info.app.*` properties                |
| `/actuator/metrics`                 | List of all metric names (JVM, HTTP, Spring Security, Tomcat)              |
| `/actuator/metrics/jvm.memory.used` | Specific metric value with tags                                            |
| `/actuator/env`                     | All property sources, active profiles, system properties (secrets masked)  |
| `/actuator/beans`                   | All Spring beans in context (useful to verify embeddingModel, vectorStore) |
| `/actuator/mappings`                | All registered @RequestMapping routes                                      |
| `/actuator/loggers`                 | All loggers and levels; POST to change at runtime                          |
| `/actuator/heapdump`                | Download JVM heap dump (.hprof) for memory analysis                        |

---

## 25. Configuration Properties Reference

All configurable settings in `application.properties`:

```properties
# App
spring.application.name=spring-rag-app
server.port=8082

# Gemini — key from GEMINI_API_KEY env var (never hardcoded)
spring.ai.google.genai.api-key=${GEMINI_API_KEY:}
spring.ai.google.genai.chat.options.model=gemini-2.5-flash
spring.ai.google.genai.chat.options.temperature=0.2

# ONNX embeddings — disable Spring AI's auto-configured alternatives
spring.ai.embedding.transformer.enabled=false
spring.main.allow-bean-definition-overriding=true
spring.ai.google.genai.embedding.enabled=false

# Retry — 1 attempt only; don't burn Gemini quota on retries
spring.ai.retry.max-attempts=1
spring.ai.retry.on-client-errors=false

# JWT
app.jwt.secret=<hex-encoded 32-byte key>
app.jwt.expiration-ms=3600000        # 1 hour

# File upload
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB

# RAG chunking
app.rag.chunk-size=800               # tokens per chunk
app.rag.chunk-overlap=100            # overlap between chunks
app.rag.top-k=4                      # chunks retrieved per query

# Chat history (conversational memory)
app.rag.chat-history.max-turns=5     # sliding window size
app.rag.chat-history.ttl-minutes=30  # idle session expiry

# Actuator
management.endpoints.web.exposure.include=health,info,metrics,env,beans,mappings,loggers,heapdump
management.endpoint.health.show-details=when-authorized
management.endpoint.health.show-components=always
management.info.env.enabled=true
info.app.name=Spring RAG App
info.app.description=RAG application using Gemini 2.5 Flash + ONNX all-MiniLM-L6-v2
info.app.version=0.0.1-SNAPSHOT
```

---

## 26. Complete Request Flow — End to End

### Scenario: User uploads a document, asks a question, asks a follow-up

**Phase 1: Login**

```
POST /auth/login  { "username": "admin", "password": "admin123" }
→ AuthController → AuthenticationManager.authenticate()
→ BCrypt verifies password against stored hash
→ JwtService.generateToken() → HS384 signed JWT
→ Response: { "token": "eyJ..." }
```

**Phase 2: Upload**

```
POST /api/documents/upload  (multipart/form-data, file=myDoc.pdf)
Authorization: Bearer eyJ...

→ JwtAuthenticationFilter: verify JWT, set SecurityContext (admin)
→ Spring Security: authenticated ✓
→ DocumentController.uploadDocument()
→ DocumentService.ingestDocument(file)
    → UUID documentId = "f1a2b3..."
    → TikaDocumentReader: extract text from PDF
    → TokenTextSplitter: split into N chunks of 800 tokens
    → each chunk.metadata["documentId"] = "f1a2b3..."
    → OnnxEmbeddingModel.call(chunks): each chunk → float[384]
    → SimpleVectorStore.add(chunks): stored in memory
    → documentStore.put("f1a2b3...", DocumentInfo(...))
→ Response: { "documentId": "f1a2b3...", "totalChunks": 7, ... }
```

**Phase 3: First query (new session)**

```
POST /api/documents/f1a2b3.../query
Authorization: Bearer eyJ...
(no X-Session-Id)

{ "question": "What is the main conclusion of this document?" }

→ JWT validated → SecurityContext set
→ QueryController.queryDocument("f1a2b3...", request, null)
→ QueryService.query("f1a2b3...", request, null)
    → resolveSessionId(null) → UUID.randomUUID() → "sess-abc"
    → OnnxEmbeddingModel: question → float[384]
    → SimpleVectorStore.similaritySearch(question, filter="documentId=='f1a2b3...'", topK=4)
        → cosine similarity against all chunks with matching documentId
        → returns top 4 chunks
    → chatHistoryService.getHistory("sess-abc") → [] (empty — new session)
    → Build Gemini prompt:
        System: "You are helpful... Context: <4 chunks>"
        (no history messages)
        User: "What is the main conclusion...?"
    → ChatClient.prompt()...call().content() → Gemini API call
    → answer = "The document concludes that..."
    → chatHistoryService.addTurn("sess-abc", question, answer)
        → sessions.computeIfAbsent("sess-abc",...) creates new SessionData
        → Deque: [(Q1, A1)]
→ Response: { "sessionId": "sess-abc", "answer": "The document concludes...", ... }
```

**Phase 4: Follow-up query (reuse session)**

```
POST /api/documents/f1a2b3.../query
Authorization: Bearer eyJ...
X-Session-Id: sess-abc

{ "question": "Can you elaborate on that conclusion?" }

→ QueryService.query("f1a2b3...", request, "sess-abc")
    → resolveSessionId("sess-abc") → "sess-abc" (reused)
    → similarity search → top 4 chunks
    → chatHistoryService.getHistory("sess-abc")
        → sessions.get("sess-abc") → found
        → lastActiveMs updated to now
        → returns [ChatTurn(Q1, A1)]
    → Build Gemini prompt:
        System: "You are helpful... Context: <4 chunks>"
        User (history): "What is the main conclusion...?"
        Assistant (history): "The document concludes that..."
        User (current): "Can you elaborate on that conclusion?"
    → Gemini API call → elaborated answer
    → chatHistoryService.addTurn("sess-abc", Q2, A2)
        → Deque: [(Q1, A1), (Q2, A2)]
→ Response: { "sessionId": "sess-abc", "answer": "To elaborate...", ... }
```

---

## 27. What Changed: Base RAG vs Conversational RAG

This section is a complete, file-by-file diff of every change made to implement conversational memory.

### New files created (2 files)

**`src/main/java/com/ragapp/dto/ChatTurn.java`** — brand new

```java
package com.ragapp.dto;

import java.time.LocalDateTime;

public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}
```

A Java 16 `record` — one Q&A turn. Immutable. Auto-generates constructor, getters, `equals`, `hashCode`, `toString`. Three fields: the question text, Gemini's answer text, and when it happened.

**`src/main/java/com/ragapp/query/ChatHistoryService.java`** — brand new

The complete session management service. See [Section 10](#10-chathistoryservice--complete-code-walkthrough) for full line-by-line explanation.

---

### Modified files (5 files)

#### `SpringRagApplication.java` — 1 annotation added

```java
// BEFORE:
@SpringBootApplication(exclude = {TransformersEmbeddingModelAutoConfiguration.class})
public class SpringRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}

// AFTER:
@SpringBootApplication(exclude = {TransformersEmbeddingModelAutoConfiguration.class})
@EnableScheduling                    // ← THIS LINE ADDED
public class SpringRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}
```

`@EnableScheduling` activates Spring's task scheduling infrastructure. Without it, the `@Scheduled` annotation on `ChatHistoryService.evictExpiredSessions()` is silently ignored.

---

#### `dto/QueryResponse.java` — 1 field added

```java
// BEFORE:
public record QueryResponse(
    String answer,
    String question,
    String documentId,
    List<String> relevantChunks
) {}

// AFTER:
public record QueryResponse(
    String sessionId,          // ← ADDED (first field)
    String answer,
    String question,
    String documentId,
    List<String> relevantChunks
) {}
```

`sessionId` was added as the **first** field. In Java records, the constructor parameter order determines the JSON serialization order. By putting `sessionId` first, it appears first in the response body, making it easy to spot and copy from the Postman response.

The client uses this returned `sessionId` on its first call (when it didn't send a header) — it saves this value and sends it as `X-Session-Id` on subsequent requests.

---

#### `query/QueryController.java` — 1 parameter added

```java
// BEFORE:
@PostMapping("/{documentId}/query")
public ResponseEntity<QueryResponse> queryDocument(
        @PathVariable String documentId,
        @Valid @RequestBody QueryRequest request
) {
    QueryResponse response = queryService.query(documentId, request);
    return ResponseEntity.ok(response);
}

// AFTER:
@PostMapping("/{documentId}/query")
public ResponseEntity<QueryResponse> queryDocument(
        @PathVariable String documentId,
        @Valid @RequestBody QueryRequest request,
        @RequestHeader(value = "X-Session-Id", required = false) String sessionId   // ← ADDED
) {
    QueryResponse response = queryService.query(documentId, request, sessionId);    // ← sessionId passed
    return ResponseEntity.ok(response);
}
```

`@RequestHeader(value = "X-Session-Id", required = false)` — Spring reads the `X-Session-Id` HTTP header and injects it as the `sessionId` parameter. `required = false` means if the header is absent, `sessionId` is `null` (no 400 error).

---

#### `query/GlobalQueryController.java` — same change as QueryController

```java
// BEFORE:
@PostMapping
public ResponseEntity<QueryResponse> queryAllDocuments(
        @Valid @RequestBody QueryRequest request
) {
    QueryResponse response = queryService.queryAllDocuments(request);
    return ResponseEntity.ok(response);
}

// AFTER:
@PostMapping
public ResponseEntity<QueryResponse> queryAllDocuments(
        @Valid @RequestBody QueryRequest request,
        @RequestHeader(value = "X-Session-Id", required = false) String sessionId   // ← ADDED
) {
    QueryResponse response = queryService.queryAllDocuments(request, sessionId);    // ← sessionId passed
    return ResponseEntity.ok(response);
}
```

---

#### `query/QueryService.java` — multiple changes

**Change 1: New field and constructor parameter**

```java
// BEFORE — 3 dependencies:
private final SimpleVectorStore vectorStore;
private final ChatClient chatClient;
private final DocumentService documentService;

public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel,
                    DocumentService documentService) {
    this.vectorStore = vectorStore;
    this.chatClient = ChatClient.builder(chatModel).build();
    this.documentService = documentService;
}

// AFTER — 4 dependencies:
private final SimpleVectorStore vectorStore;
private final ChatClient chatClient;
private final DocumentService documentService;
private final ChatHistoryService chatHistoryService;   // ← ADDED

public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel,
                    DocumentService documentService, ChatHistoryService chatHistoryService) {
    this.vectorStore = vectorStore;
    this.chatClient = ChatClient.builder(chatModel).build();
    this.documentService = documentService;
    this.chatHistoryService = chatHistoryService;   // ← ADDED
}
```

**Change 2: Method signatures — added `sessionId` parameter**

```java
// BEFORE:
public QueryResponse query(String documentId, QueryRequest request) { ... }
public QueryResponse queryAllDocuments(QueryRequest request) { ... }

// AFTER:
public QueryResponse query(String documentId, QueryRequest request, String sessionId) { ... }
public QueryResponse queryAllDocuments(QueryRequest request, String sessionId) { ... }
```

**Change 3: Added `resolveSessionId` helper (new private method)**

```java
private String resolveSessionId(String sessionId) {
    return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
}
```

**Change 4: `buildResponse` — injected history into Gemini prompt**

```java
// BEFORE — buildResponse did NOT take sessionId, had NO history logic:
private QueryResponse buildResponse(String scope, String question, List<Document> relevantDocs) {
    String context = relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));

    String answer = chatClient.prompt()
            .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
            .user(question)
            .call()
            .content();

    // returned QueryResponse without sessionId field
    return new QueryResponse(answer, question, scope, chunks);
}

// AFTER — buildResponse takes sessionId, fetches history, injects into prompt, saves turn:
private QueryResponse buildResponse(String scope, String sessionId, String question, List<Document> relevantDocs) {
    String context = relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));

    // ← NEW: fetch history
    List<ChatTurn> history = chatHistoryService.getHistory(sessionId);
    List<Message> historyMessages = new ArrayList<>();
    for (ChatTurn turn : history) {
        historyMessages.add(new UserMessage(turn.question()));
        historyMessages.add(new AssistantMessage(turn.answer()));
    }

    String answer = chatClient.prompt()
            .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
            .messages(historyMessages)   // ← NEW: inject history
            .user(question)
            .call()
            .content();

    // ← NEW: save this turn
    chatHistoryService.addTurn(sessionId, question, answer);

    // returned QueryResponse now includes sessionId
    return new QueryResponse(sessionId, answer, question, scope, chunks);
}
```

---

#### `application.properties` — 2 new properties added

```properties
# BEFORE: no chat-history properties

# AFTER: two new properties added
app.rag.chat-history.max-turns=5      # ← NEW
app.rag.chat-history.ttl-minutes=30   # ← NEW
```

Also the API key was changed to use an environment variable (separate from conversational memory):

```properties
# BEFORE (API key hardcoded — revoked by Google scanner):
spring.ai.google.genai.api-key=AIzaSy...

# AFTER (API key from environment variable):
spring.ai.google.genai.api-key=${GEMINI_API_KEY:}
```

`${GEMINI_API_KEY:}` — Spring's property placeholder. Reads the `GEMINI_API_KEY` environment variable. The `:` at the end means "empty string if not set" (prevents startup failure when the env var is missing).

---

## 28. In-Memory Data Structure — What Lives in RAM

Here is a complete picture of what is stored in the JVM heap while the app is running.

### SimpleVectorStore (document storage)

```
SimpleVectorStore.store:
ConcurrentHashMap<String, Document> {
  "uuid-chunk-1" → Document {
    text:      "Payment terms are Net-30. Invoice must be...",
    embedding: float[384] { 0.23f, -0.11f, 0.84f, ... },
    metadata:  { "documentId": "f1a2b3-...", "source": "contract.pdf" }
  },
  "uuid-chunk-2" → Document {
    text:      "Late fees of 2% per month apply after day 31...",
    embedding: float[384] { 0.21f, -0.09f, 0.79f, ... },
    metadata:  { "documentId": "f1a2b3-...", "source": "contract.pdf" }
  },
  ... (one entry per chunk, for every uploaded document)
}
```

Each `float[384]` takes 384 × 4 bytes = **1,536 bytes = 1.5 KB** per chunk. A 10-page PDF producing 30 chunks would use 30 × 1.5 KB = **45 KB** just for embeddings (plus the text itself).

### DocumentService.documentStore (document registry)

```
documentStore:
ConcurrentHashMap<String, DocumentInfo> {
  "f1a2b3-..." → DocumentInfo {
    documentId: "f1a2b3-...",
    filename:   "contract.pdf",
    chunks:     30
  },
  "a7b8c9-..." → DocumentInfo {
    documentId: "a7b8c9-...",
    filename:   "policy.docx",
    chunks:     15
  }
}
```

### ChatHistoryService.sessions (conversational memory)

```
sessions:
ConcurrentHashMap<String, SessionData> {
  "sess-abc-123" → SessionData {
    turns: ArrayDeque<ChatTurn> [
      HEAD → ChatTurn {
               question:  "What are the payment terms?",
               answer:    "The payment terms are Net-30, meaning...",
               timestamp: 2026-05-02T10:15:23
             },
             ChatTurn {
               question:  "Can you elaborate on the late fee?",
               answer:    "Late fees are 2% per month starting from day 31...",
               timestamp: 2026-05-02T10:16:45
             }  ← TAIL
    ],
    lastActiveMs: AtomicLong(1746177405000)  ← epoch ms of last activity
  },
  "sess-xyz-456" → SessionData {
    turns: ArrayDeque<ChatTurn> [ ... ],
    lastActiveMs: AtomicLong(...)
  }
}
```

### Everything is lost on restart

**SimpleVectorStore** and **ChatHistoryService** both store data in JVM heap memory. When the application stops (or crashes), all of this is lost:

- All uploaded documents and their vector embeddings disappear
- All conversation sessions and their history disappear

For production, you would use:

- **pgvector** (PostgreSQL extension) or **Pinecone** for persistent vector storage
- **Redis** or a database for session/conversation history persistence

---

## 29. Interview Questions & Model Answers

**Q: What is RAG and why do we use it?**
RAG (Retrieval Augmented Generation) solves the knowledge gap in LLMs. LLMs are trained on public data and don't know about your private documents. RAG retrieves the most relevant document chunks using vector similarity search and injects them as context into the LLM prompt. The LLM is constrained to only use that context, which prevents hallucination and gives grounded, accurate answers about your specific documents.

**Q: What is an embedding and why is it needed?**
An embedding is a numerical representation of text — a fixed-length array of floats (in our case, 384 dimensions) where the values encode the semantic meaning. Two pieces of text with similar meaning will have vectors that point in a similar direction in 384D space. We need embeddings because computers can't measure "meaning" directly — but they can compute distances between vectors. Without embeddings, we could only do keyword search (which misses synonyms and paraphrases).

**Q: Why did you use ONNX instead of the Google embedding API?**
Three reasons: cost (free vs. paid per token), privacy (data never leaves the machine), and offline operation (works without internet after the first model download). The ONNX model runs locally on CPU in about 5-50ms per batch. The quality is excellent — all-MiniLM-L6-v2 is a proven sentence-embedding model used in production systems.

**Q: How does the session ID work?**
The server generates a UUID v4 on the first request (when no `X-Session-Id` header is present) and returns it in the response body as `sessionId`. The client saves this value and sends it as the `X-Session-Id` header on subsequent requests. The server uses it as a key in a `ConcurrentHashMap` to look up the conversation history for that session. No cookies, no server-side HTTP sessions — it's purely application-level state management.

**Q: Why ConcurrentHashMap and not HashMap?**
The `ChatHistoryService` is a Spring singleton shared across all request-handling threads. Multiple HTTP requests arrive concurrently on different Tomcat threads. `HashMap` is not thread-safe — concurrent puts can corrupt its internal state. `ConcurrentHashMap` uses lock striping (16 segments), allowing concurrent writes on different keys with no blocking, while using fine-grained locking only when two threads access the same key.

**Q: Why synchronized on the Deque if you already use ConcurrentHashMap?**
`ConcurrentHashMap` makes the map operations safe, but it doesn't make the values safe. The `ArrayDeque` inside each `SessionData` value is not thread-safe. Two threads could try to read and modify the same session's Deque simultaneously — for example, one thread reads history while another appends a new turn. The `synchronized (data.turns())` block uses the Deque instance as a monitor to ensure only one thread can modify or read a specific session's history at a time.

**Q: Why is JWT stateless and what are the advantages?**
JWT is stateless because the server doesn't store anything — the token itself contains all the information needed to verify the user (username, roles, expiry), signed with a secret key. This means any server instance can validate any token independently without querying a database or session store. This is crucial for horizontal scaling (multiple server instances), microservices, and high availability.

**Q: What happens when the 5-turn limit is hit?**
When the Deque reaches 5 turns and a new turn arrives, `pollFirst()` removes the oldest turn from the head before `addLast()` adds the new turn to the tail. This is a sliding window — the oldest conversation is automatically evicted to make room for the newest. The system always maintains the most recent 5 turns, which is the most contextually relevant history.

**Q: What happens when a session times out?**
A `@Scheduled` method runs every 5 minutes. It computes a cutoff timestamp (now minus 30 minutes) and calls `removeIf` on the `ConcurrentHashMap` to evict all sessions whose `lastActiveMs` is older than the cutoff. If the client then sends a request with the expired session ID, `getHistory()` returns an empty list (session not found). The query proceeds with no history — as if it's a new conversation. The server uses the same session ID the client sent, so the client's perspective is that the conversation continues, just without the old context.

**Q: What is mean pooling and why is it needed in the embedding process?**
The ONNX transformer model produces one 384-dimensional vector per token (not per sentence). A sentence of 12 tokens produces 12 vectors. We need one vector per sentence, so we average all token vectors — weighted by the attention mask to exclude padding tokens. This "mean pooling" produces a single vector that represents the entire sentence's meaning. It's more stable than just taking the `[CLS]` token vector because it incorporates information from all tokens.

**Q: How does the per-document vs. global query work?**
Both use vector similarity search on the same `SimpleVectorStore`. The difference is the filter expression. Per-document queries pass `filterExpression = "documentId == '{id}'"` so only chunks tagged with that document's UUID are candidates. Global queries don't pass a filter, so every stored chunk across all uploaded documents is a candidate. The same cosine similarity ranking applies — the top 4 most relevant chunks are returned regardless.

**Q: What is a Java record and why did you use it for ChatTurn?**
A record is a Java 16+ feature for immutable data carriers. The single line `public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}` auto-generates a canonical constructor, three accessor methods (`question()`, `answer()`, `timestamp()`), `equals()`, `hashCode()`, and `toString()`. We used it for `ChatTurn` because a Q&A turn is pure data — it has no behavior, should never change after creation, and the boilerplate-free syntax keeps the code clean.

**Q: Why did you add @EnableScheduling to the main class?**
`@EnableScheduling` activates Spring's task scheduling infrastructure at startup. Without it, the `@Scheduled` annotation on methods is completely ignored — no background thread is created, no method is ever called. We added it because `ChatHistoryService.evictExpiredSessions()` uses `@Scheduled(fixedRate = 300_000)` to clean up idle sessions every 5 minutes. This annotation was the only change needed to the main class to enable the entire TTL cleanup mechanism.

**Q: Why is `computeIfAbsent` used instead of `get` + `put` for creating new sessions?**
`get` + `put` is not atomic. If two threads simultaneously try to create the same session for the first time, both would see `get()` return null, both would create a new `SessionData`, and one would overwrite the other's. The turn added by the first thread would be in a `SessionData` that's no longer referenced by the map — lost. `ConcurrentHashMap.computeIfAbsent` is guaranteed to call the lambda at most once per key even under concurrent access. The winner's `SessionData` is stored; the loser gets back the same stored value without creating a new one.

**Q: Why store history server-side instead of having the client send the history in every request?**
Three reasons: First, payload size — sending 5 turns of Q&A in every request body is wasteful (hundreds of characters per turn, multiplied by concurrent users). The client just sends a 36-character UUID. Second, security — the server controls the window size and TTL; a malicious client cannot inject fake history or bypass limits. Third, simplicity — the client only needs to remember one string (the session ID), not manage conversation state.

**Q: What is Apache Tika and why do you use it for document parsing?**
Apache Tika is a content analysis toolkit that can extract text from over 1,000 file formats using a single API. It auto-detects the file type from magic bytes (not the file extension), then uses the appropriate parser — PDFBox for PDFs, Apache POI for Office documents, NekoHTML for HTML, etc. We use it because the app needs to accept any document type the user might upload, and Tika handles all the format-specific complexity under a single `TikaDocumentReader` class.

**Q: What is chunking and why is the overlap (100 tokens) important?**
Chunking splits a document into smaller pieces because embedding models have a maximum input length (~512 tokens) and the ONNX model needs to embed each piece. Overlap (100 tokens shared between adjacent chunks) is important to prevent information loss at chunk boundaries. Consider a sentence that starts at token 750 and ends at token 820 in a document. With a 800-token chunk size and no overlap, this sentence would be split — half in chunk 0, half in chunk 1. With 100-token overlap, chunk 1 starts at token 700, so this sentence is fully present in chunk 1. This ensures retrieval can find complete, contextually whole passages.

**Q: How does SimpleVectorStore do similarity search? Is it O(n)?**
Yes, it's O(n) — a linear scan. For every query, it computes cosine similarity between the query vector and every single stored chunk vector, then returns the top-K. This is called "exact search" or "brute-force search." For a development app with a few hundred chunks, this is perfectly fast (milliseconds). For production with millions of chunks, you'd use an Approximate Nearest Neighbor (ANN) index (like HNSW used in Pinecone, pgvector, Milvus) which gives O(log n) search at the cost of approximate (not exact) results.

**Q: What is the temperature setting (0.2) in Gemini and why did you choose it?**
Temperature controls the randomness of the LLM's output. 0.0 = fully deterministic (same input → same output every time). 1.0 = very creative/random. We use 0.2 because this is a document Q&A application — we want consistent, factual, deterministic answers based on the document content, not creative interpretation. Low temperature keeps the model grounded to the provided context. Higher temperature would make it more likely to "creatively" fill in details not in the document (hallucinate).

**Q: What happens if the GEMINI_API_KEY environment variable is not set?**
The property `spring.ai.google.genai.api-key=${GEMINI_API_KEY:}` uses Spring's property placeholder with a default of empty string (`:` with no value after). The application starts successfully but every Gemini API call returns a 401 (unauthorized) or similar error because the API key is empty. The `GlobalExceptionHandler` catches this as a `ClientException` and returns a 502 response. Importantly, the API key is never hardcoded in the source code — Google's automated scanners scan public GitHub repositories and revoke any API keys they find.

**Q: Walk me through what happens when I send two simultaneous requests with the same session ID.**
Thread A and Thread B both arrive at `ChatHistoryService.getHistory("same-id")`. Both call `sessions.get("same-id")` — this is lock-free on `ConcurrentHashMap`, both get the same `SessionData` reference safely. Then both try to enter `synchronized (data.turns())`. Only one succeeds — say Thread A. Thread A creates a snapshot `ArrayList` of the Deque and exits the synchronized block. Thread B then enters, creates its own snapshot. Both snapshots contain identical data (assuming no `addTurn` happened in between). Both threads proceed to call Gemini in parallel (the actual Gemini HTTP call is outside the synchronized block — it's concurrent, which is correct). When both get answers, both call `addTurn` — each one enters the `synchronized (data.turns())` block exclusively. Two turns are added, one after the other. The Deque ends up with both turns.

**Q: How does the Spring Security filter chain know which endpoints need auth and which don't?**
`SecurityConfig.java` defines the rules:
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/**").permitAll()       // login — no token
    .requestMatchers("/actuator/health").permitAll() // health check — no token
    .requestMatchers("/actuator/info").permitAll()   // info — no token
    .requestMatchers("/actuator/**").hasRole("ADMIN") // other actuator — admin only
    .anyRequest().authenticated()                    // everything else — any valid JWT
)
```
The `JwtAuthenticationFilter` runs first and attempts to authenticate from the JWT header. If it succeeds, the `SecurityContextHolder` has an authenticated user. If the JWT is missing (anonymous request) and the endpoint is `permitAll`, the request passes. If the endpoint requires authentication and the context is empty, the `AuthorizationFilter` rejects with 401.

**Q: What is the difference between `fixedRate` and `fixedDelay` in @Scheduled?**
`fixedRate = N` means: start the next execution N milliseconds after the **start** of the previous execution, regardless of how long it took. If execution takes 100ms and N=300ms, the next starts at T+300ms.
`fixedDelay = N` means: wait N milliseconds after the **end** of the previous execution. If execution takes 100ms and N=300ms, the next starts at T+400ms.
For the TTL cleanup (`fixedRate = 300_000`), we chose `fixedRate` because we want the cleanup to run on a regular wall-clock schedule (every 5 minutes), not be pushed back if the cleanup itself takes longer.

**Q: Why does the app exclude `TransformersEmbeddingModelAutoConfiguration`?**
Spring AI's `TransformersEmbeddingModel` auto-configuration uses DJL (Deep Java Library) for mean pooling, which on first use tries to download PyTorch native binaries from the internet. In corporate network environments with SSL certificate inspection, this download fails with SSL handshake errors. By excluding the auto-configuration and implementing our own `OnnxEmbeddingModel` bean, we do mean pooling in pure Java (no native downloads needed) and use ONNX Runtime for model inference. The result is identical embedding quality with no network dependency after the initial model file download.

**Q: What is `@Primary` on the `embeddingModel` bean and why is it needed?**
When multiple beans of the same type exist in the Spring context, `@Primary` tells Spring "use THIS one when no specific qualifier is given." Our `AiConfig.embeddingModel()` is annotated with `@Primary` because the `spring-ai-transformers` dependency might auto-configure its own `EmbeddingModel` bean (despite the auto-configuration exclusion, there can be edge cases). `@Primary` ensures that `SimpleVectorStore` and any other component that depends on `EmbeddingModel` always gets our custom `OnnxEmbeddingModel`, not some other accidentally-registered bean.

**Q: How does the Postman collection use the sessionId from the response?**
The Postman collection has a `sessionId` collection variable. The query request tests (under the "Tests" tab in Postman) have a script:
```javascript
var jsonData = pm.response.json();
if (jsonData.sessionId) {
    pm.collectionVariables.set("sessionId", jsonData.sessionId);
}
```
This auto-saves the `sessionId` from the response into the collection variable. The request headers include `X-Session-Id: {{sessionId}}`. So on the first request, the header is blank (or empty), the server generates a new UUID, the test script saves it, and on all subsequent requests, the saved UUID is sent automatically. This simulates how a real client would manage sessions.

