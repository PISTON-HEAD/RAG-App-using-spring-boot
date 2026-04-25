package com.ragapp.query;

import com.ragapp.document.DocumentService;
import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final SimpleVectorStore vectorStore;
    private final ChatClient chatClient;
    private final DocumentService documentService;

    @Value("${app.rag.top-k:4}")
    private int topK;

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that answers questions based on the provided context.
            Use ONLY the information from the context below to answer the question.
            If the context doesn't contain enough information to answer, say so clearly.
            Do not make up information that is not in the context.
            
            Context:
            {context}
            """;

    public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel, DocumentService documentService) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.documentService = documentService;
    }

    public QueryResponse query(String documentId, QueryRequest request) {
        if (!documentService.documentExists(documentId)) {
            throw new IllegalArgumentException("Document not found: " + documentId);
        }

        // 1. Similarity search — retrieve relevant chunks filtered by documentId
        String filterExpression = "documentId == '" + documentId + "'";
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.query(request.question())
                        .withTopK(topK)
                        .withFilterExpression(filterExpression)
        );

        return buildResponse(documentId, request.question(), relevantDocs);
    }

    /**
     * Cross-document query: searches across ALL uploaded documents.
     * No documentId filter — every stored chunk is a candidate.
     * Returns the documentId as "ALL" to indicate a global search.
     */
    public QueryResponse queryAllDocuments(QueryRequest request) {
        // No filter expression — search across every chunk in the vector store
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.query(request.question())
                        .withTopK(topK)
        );

        return buildResponse("ALL_DOCUMENTS", request.question(), relevantDocs);
    }

    private QueryResponse buildResponse(String scope, String question, List<Document> relevantDocs) {
        // 2. Build context from retrieved chunks
        String context = relevantDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. Build prompt and call LLM
        String answer = chatClient.prompt()
                .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
                .user(question)
                .call()
                .content();

        // 4. Return structured response (show first 200 chars of each chunk)
        List<String> chunks = relevantDocs.stream()
                .map(doc -> {
                    String content = doc.getContent();
                    return content.substring(0, Math.min(content.length(), 200)) + "...";
                })
                .toList();

        return new QueryResponse(answer, question, scope, chunks);
    }
}
