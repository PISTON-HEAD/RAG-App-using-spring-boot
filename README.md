# Spring Boot RAG Application

A mini **Retrieval-Augmented Generation (RAG)** application built with **Spring Boot**, **Spring AI**, and **JWT authentication**.

## Architecture

```
Client ──► Auth (JWT) ──► Upload Document ──► Index (Chunk → Embed → VectorStore)
                │
                └──► Query Document ──► Similarity Search → LLM → Answer
```

## Tech Stack

| Component        | Technology                         |
| ---------------- | ---------------------------------- |
| Framework        | Spring Boot 3.3                    |
| AI / RAG         | Spring AI + OpenAI                 |
| Embeddings       | text-embedding-ada-002             |
| Chat Model       | gpt-4o-mini                        |
| Vector Store     | SimpleVectorStore (in-memory)      |
| Document Parsing | Apache Tika (PDF, TXT, DOCX, etc.) |
| Authentication   | Spring Security + JWT              |
| Validation       | Jakarta Validation                 |

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **OpenAI API Key**

## Quick Start

### 1. Set your OpenAI API key

```bash
export OPENAI_API_KEY=sk-your-key-here
```

Or on Windows:

```powershell
$env:OPENAI_API_KEY = "sk-your-key-here"
```

### 2. Build & Run

```bash
mvn clean install
mvn spring-boot:run
```

The app will start on `http://localhost:8080`.

## API Usage

### Step 1: Login

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

**Response:**

```json
{
  "token": "eyJhbGci...",
  "username": "admin"
}
```

### Step 2: Upload a Document

```bash
curl -X POST http://localhost:8080/documents/upload \
  -H "Authorization: Bearer <your-jwt-token>" \
  -F "file=@/path/to/your-document.pdf"
```

**Response:**

```json
{
  "documentId": "abc-123-...",
  "filename": "your-document.pdf",
  "totalChunks": 15,
  "message": "Document indexed successfully"
}
```

### Step 3: Query the Document

```bash
curl -X POST http://localhost:8080/documents/abc-123-.../query \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the main topic of this document?"}'
```

**Response:**

```json
{
  "answer": "Based on the document, the main topic is...",
  "question": "What is the main topic of this document?",
  "documentId": "abc-123-...",
  "relevantChunks": ["chunk preview 1...", "chunk preview 2..."]
}
```

### List Documents

```bash
curl http://localhost:8080/documents \
  -H "Authorization: Bearer <your-jwt-token>"
```

## Users (Hardcoded)

| Username | Password | Role  |
| -------- | -------- | ----- |
| admin    | admin123 | ADMIN |
| user     | user123  | USER  |

## Project Structure

```
src/main/java/com/ragapp/
├── SpringRagApplication.java        # Main entry point
├── config/
│   ├── SecurityConfig.java          # Spring Security + JWT config
│   └── AiConfig.java               # Vector store bean
├── auth/
│   ├── AuthController.java          # POST /auth/login
│   ├── JwtService.java              # JWT token generation & validation
│   └── JwtAuthenticationFilter.java # JWT filter in security chain
├── document/
│   ├── DocumentController.java      # POST /documents/upload, GET /documents
│   └── DocumentService.java         # Parse → Chunk → Embed → Store
├── query/
│   ├── QueryController.java         # POST /documents/{id}/query
│   └── QueryService.java            # Similarity search → LLM → Answer
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   ├── DocumentUploadResponse.java
│   ├── DocumentInfo.java
│   ├── QueryRequest.java
│   └── QueryResponse.java
└── exception/
    └── GlobalExceptionHandler.java  # Centralized error handling
```

## RAG Workflow Detail

1. **Upload** — Document is received via multipart upload
2. **Parse** — Apache Tika extracts text from PDF/DOCX/TXT
3. **Chunk** — Text is split into ~800-token chunks with 100-token overlap
4. **Embed** — Each chunk is embedded using OpenAI `text-embedding-ada-002`
5. **Store** — Embeddings are stored in an in-memory vector store
6. **Query** — User's question is embedded, similarity search finds top-K chunks
7. **Augment** — Retrieved chunks are injected into the LLM prompt as context
8. **Generate** — GPT-4o-mini generates an answer grounded in the context
