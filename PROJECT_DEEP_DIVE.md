# Spring RAG App — Complete Technical Deep Dive

> Personal interview reference. Everything you need to explain this project confidently.

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
10. [ConcurrentHashMap vs HashMap](#10-concurrenthashmap-vs-hashmap)
11. [Why Deque? How It Stores Q&A](#11-why-deque-how-it-stores-qa)
12. [Why synchronized on the Deque?](#12-why-synchronized-on-the-deque)
13. [Session ID — Generation, Lifecycle, Expiry](#13-session-id--generation-lifecycle-expiry)
14. [Why 5 Turns? Why Not Unlimited?](#14-why-5-turns-why-not-unlimited)
15. [What Happens When a Session Expires?](#15-what-happens-when-a-session-expires)
16. [Spring Security Filter Chain](#16-spring-security-filter-chain)
17. [Exception Handling Strategy](#17-exception-handling-strategy)
18. [Actuator — Monitoring & Observability](#18-actuator--monitoring--observability)
19. [Configuration Properties Reference](#19-configuration-properties-reference)
20. [Complete Request Flow — End to End](#20-complete-request-flow--end-to-end)
21. [What Changed: Base RAG vs Conversational RAG](#21-what-changed-base-rag-vs-conversational-rag)
22. [Interview Questions & Model Answers](#22-interview-questions--model-answers)

---

## 1. Project Overview

This is a **Spring Boot RAG (Retrieval Augmented Generation) application** with **conversational memory**.

### What it does

Users upload documents (PDF, DOCX, TXT, etc.). They can then ask natural language questions. The system:

1. Converts the question into a mathematical vector (embedding)
2. Searches the uploaded document chunks for the most relevant pieces
3. Feeds those pieces as context to Google Gemini (the LLM)
4. Gemini generates a grounded answer — it can only use the provided context, not its training data
5. The conversation history from the session is also sent so the LLM understands follow-up questions

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

Before this feature, every query was independent:

```
Turn 1: "What is Spring Boot?"
        → Gemini: "Spring Boot is a Java framework..."

Turn 2: "Tell me more about its auto-configuration"
        → Gemini had NO idea what "its" referred to
        → It would either hallucinate or ask for clarification
```

### The solution: inject history into the Gemini prompt

With conversational memory, the Gemini prompt becomes:

```
[System]
You are a helpful assistant... Context: {RAG chunks}

[User]        ← Turn 1 question (from history)
What is Spring Boot?

[Assistant]   ← Turn 1 answer (from history)
Spring Boot is a Java framework that simplifies...

[User]        ← Turn 2 question (from history)
Tell me more about its auto-configuration.

[Assistant]   ← Turn 2 answer (from history)
Spring Boot's auto-configuration works by...

[User]        ← CURRENT question
How does auto-configuration decide which beans to create?
```

Now Gemini fully understands "auto-configuration" from the context of the conversation.

### Key design decision: we store history server-side

The client only sends a `sessionId` (UUID). The server holds all the Q&A pairs. This is intentional:

- The client doesn't need to re-send its entire chat history on every request
- Reduces request payload size significantly (imaginge 20 turns of Q&A in every request body)
- The server controls the window size (max 5 turns) — client can't bypass this

---

## 10. ConcurrentHashMap vs HashMap

### Why not just use `HashMap`?

Our `ChatHistoryService` is a **Spring `@Service` bean** — it's a **singleton** shared across the entire application. Multiple HTTP requests arrive **simultaneously** (on different Tomcat threads). If two requests both call `sessions.put(...)` or `sessions.get(...)` at the same moment on a regular `HashMap`, you get:

- **Data corruption** — HashMap can enter an inconsistent internal state during concurrent resize operations
- **Infinite loops** — A famous Java bug where two threads rehashing simultaneously create a circular linked list in the internal bucket, causing `get()` to loop forever
- **Lost updates** — Two `put()` operations on the same key, one overwrites the other silently

`ConcurrentHashMap` solves all of this with **lock striping**:

- The internal array is divided into 16 segments (by default)
- Each segment has its own lock
- Two operations on **different** keys (different segments) proceed simultaneously with no blocking
- Two operations on the **same** key use the same segment lock — one waits for the other
- `get()` is completely lock-free (reads are always safe)

**Bottom line:** In a multi-threaded server, always use `ConcurrentHashMap` over `HashMap` for shared state.

### Why not `Hashtable` or `Collections.synchronizedMap()`?

- `Hashtable` and `synchronizedMap` use a **single lock** for the entire map — only one thread can access it at a time, even if they're working on completely different keys. This is a bottleneck.
- `ConcurrentHashMap` allows up to 16 (or more) concurrent writes — far better throughput.

---

## 11. Why Deque? How It Stores Q&A

### What is a Deque?

`Deque` = Double-Ended Queue — a data structure that supports efficient insertion and removal from **both ends**:

- `addLast(x)` — adds to the tail (newest element)
- `pollFirst()` — removes from the head (oldest element)
- `peek()`, iteration — works in order from head (oldest) to tail (newest)

### Implementation: `ArrayDeque`

We use `ArrayDeque` — a resizable array implementation of `Deque`. It's backed by a circular array, making both head and tail operations O(1).

### Why Deque for a sliding window?

We need **FIFO with a fixed max size** — first in, first out:

```
State after turn 5:
    HEAD → [Q1/A1] [Q2/A2] [Q3/A3] [Q4/A4] [Q5/A5] ← TAIL

Turn 6 arrives:
    size (5) >= maxTurns (5) → pollFirst() removes [Q1/A1] from HEAD
    addLast([Q6/A6]) at TAIL

State after turn 6:
    HEAD → [Q2/A2] [Q3/A3] [Q4/A4] [Q5/A5] [Q6/A6] ← TAIL
```

A Deque is perfect for this because:

- `pollFirst()` to evict oldest: **O(1)**
- `addLast()` to add newest: **O(1)**
- Iteration gives chronological order (oldest to newest): exactly what Gemini needs

### What is stored in each ChatTurn?

```java
public record ChatTurn(String question, String answer, LocalDateTime timestamp) {}
```

- `question` — the user's question text
- `answer` — Gemini's response text
- `timestamp` — when this turn happened (for debugging/auditing)

### How it's sent to Gemini

```java
for (ChatTurn turn : history) {
    historyMessages.add(new UserMessage(turn.question()));
    historyMessages.add(new AssistantMessage(turn.answer()));
}
// Gemini's API expects messages to alternate: User, Assistant, User, Assistant, ...
```

---

## 12. Why synchronized on the Deque?

This is a subtle but important concurrency point.

```java
private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}
private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>();
```

**`ConcurrentHashMap` only makes the MAP operations thread-safe** — it protects `put`, `get`, `remove` on the map itself. It does NOT make the **values** inside the map thread-safe.

Consider this scenario:

- Thread A is reading history for session `xyz` (iterating the Deque)
- Thread B simultaneously adds a new turn to the same session's Deque
- `ArrayDeque` is **not thread-safe** — concurrent read + write can cause `ConcurrentModificationException` or corrupted iteration

That's why both `getHistory` and `addTurn` synchronize on the **Deque object itself**:

```java
// In getHistory:
synchronized (data.turns()) {
    return new ArrayList<>(data.turns()); // snapshot — safe copy
}

// In addTurn:
synchronized (data.turns()) {
    if (data.turns().size() >= maxTurns) {
        data.turns().pollFirst();
    }
    data.turns().addLast(new ChatTurn(...));
}
```

The `synchronized (data.turns())` block uses the **Deque instance as a monitor lock**. Java guarantees only one thread can hold a given object's monitor at a time. So:

- If Thread A is inside `getHistory`'s synchronized block, Thread B's `addTurn` synchronized block **waits**
- They can't execute concurrently on the same session's Deque

**Why is `lastActiveMs` an `AtomicLong` (not `synchronized`)?**
`AtomicLong` uses CPU-level compare-and-swap (CAS) instructions — a lock-free way to do atomic reads and writes on a single `long`. We only need `set()` and `get()` on it — no need for the overhead of a synchronized block. CAS is faster than synchronization for single-variable operations.

---

## 13. Session ID — Generation, Lifecycle, Expiry

### Generation

```java
private String resolveSessionId(String sessionId) {
    return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
}
```

- If the client sends `X-Session-Id: abc-123` → use `abc-123`
- If missing or blank → generate `UUID.randomUUID()` → `"3f2a1b8c-4d5e-6f7a-8b9c-0d1e2f3a4b5c"`

**UUID v4** is 128 bits of randomness. There are 2^122 possible UUIDs (~5.3 × 10^36). The probability of a collision is astronomically small — for all practical purposes, every generated UUID is globally unique.

### Full lifecycle

```
Request arrives (no X-Session-Id)
    │
    ▼
resolveSessionId() → UUID.randomUUID() → "abc-123"
    │
    ▼
chatHistoryService.getHistory("abc-123")
    → sessions.get("abc-123") returns null
    → returns empty List.of()
    → history is empty, no messages sent to Gemini
    │
    ▼
Gemini answers the question
    │
    ▼
chatHistoryService.addTurn("abc-123", question, answer)
    → sessions.computeIfAbsent("abc-123", ...) creates new SessionData
    → new ArrayDeque, new AtomicLong(now)
    → ChatTurn added to Deque
    │
    ▼
Response returned: { "sessionId": "abc-123", ... }
    │
    ▼  (Client saves sessionId, sends it next time)
    │
Next request arrives (X-Session-Id: abc-123)
    │
    ▼
resolveSessionId() → "abc-123" (reused)
    │
    ▼
chatHistoryService.getHistory("abc-123")
    → sessions.get("abc-123") → found
    → lastActiveMs updated
    → returns [ChatTurn(Q1, A1)]
    │
    ▼
[Q1, A1] injected into Gemini prompt as history
```

### The SessionData record

```java
private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}
```

`lastActiveMs` tracks when this session was last used. Updated on every `getHistory` and `addTurn` call. Used by the cleanup job to detect idle sessions.

---

## 14. Why 5 Turns? Why Not Unlimited?

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

## 15. What Happens When a Session Expires?

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

## 16. Spring Security Filter Chain

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

## 17. Exception Handling Strategy

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

## 18. Actuator — Monitoring & Observability

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

## 19. Configuration Properties Reference

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

## 20. Complete Request Flow — End to End

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

## 21. What Changed: Base RAG vs Conversational RAG

### New files added

| File                            | Purpose                                              |
| ------------------------------- | ---------------------------------------------------- |
| `dto/ChatTurn.java`             | Immutable record holding one Q&A pair with timestamp |
| `query/ChatHistoryService.java` | In-memory session store, sliding window, TTL cleanup |

### Modified files

**`SpringRagApplication.java`** — Added `@EnableScheduling`

```java
// Before
@SpringBootApplication(exclude = {...})
public class SpringRagApplication { ... }

// After
@SpringBootApplication(exclude = {...})
@EnableScheduling  // ← required for @Scheduled to work
public class SpringRagApplication { ... }
```

**`dto/QueryResponse.java`** — Added `sessionId` field

```java
// Before
public record QueryResponse(String answer, String question, String documentId, List<String> relevantChunks) {}

// After
public record QueryResponse(String sessionId, String answer, String question, String documentId, List<String> relevantChunks) {}
```

**`query/QueryService.java`** — Core changes

- Constructor now injects `ChatHistoryService`
- `query()` and `queryAllDocuments()` accept `sessionId` parameter
- `resolveSessionId()` helper: reuse or generate UUID
- `buildResponse()` fetches history, converts to `Message` objects, injects into prompt, saves turn after response

**`query/QueryController.java`** — New `@RequestHeader`

```java
// Before
public ResponseEntity<QueryResponse> queryDocument(@PathVariable String documentId, @Valid @RequestBody QueryRequest request)

// After
public ResponseEntity<QueryResponse> queryDocument(@PathVariable String documentId,
    @Valid @RequestBody QueryRequest request,
    @RequestHeader(value = "X-Session-Id", required = false) String sessionId)
```

**`query/GlobalQueryController.java`** — Same header change

**`application.properties`** — Two new properties

```properties
app.rag.chat-history.max-turns=5
app.rag.chat-history.ttl-minutes=30
```

---

## 22. Interview Questions & Model Answers

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
