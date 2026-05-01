package com.ragapp.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ragapp.document.DocumentService;
import com.ragapp.dto.ChatTurn;
import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;

@Service
public class QueryService {

    private final SimpleVectorStore vectorStore;
    private final ChatClient chatClient;
    private final DocumentService documentService;
    private final ChatHistoryService chatHistoryService;

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

    public QueryService(SimpleVectorStore vectorStore, ChatModel chatModel,
                        DocumentService documentService, ChatHistoryService chatHistoryService) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.documentService = documentService;
        this.chatHistoryService = chatHistoryService;
    }

    public QueryResponse query(String documentId, QueryRequest request, String sessionId) {
        if (!documentService.documentExists(documentId)) {
            throw new IllegalArgumentException("Document not found: " + documentId);
        }

        String resolvedSession = resolveSessionId(sessionId);

        String filterExpression = "documentId == '" + documentId + "'";
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(request.question())
                        .topK(topK)
                        .filterExpression(filterExpression)
                        .build()
        );

        return buildResponse(documentId, resolvedSession, request.question(), relevantDocs);
    }

    public QueryResponse queryAllDocuments(QueryRequest request, String sessionId) {
        String resolvedSession = resolveSessionId(sessionId);

        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(request.question())
                        .topK(topK)
                        .build()
        );

        return buildResponse("ALL_DOCUMENTS", resolvedSession, request.question(), relevantDocs);
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
    }

    private QueryResponse buildResponse(String scope, String sessionId, String question, List<Document> relevantDocs) {
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // Build history messages (alternating user/assistant for previous turns)
        List<ChatTurn> history = chatHistoryService.getHistory(sessionId);
        List<Message> historyMessages = new ArrayList<>();
        for (ChatTurn turn : history) {
            historyMessages.add(new UserMessage(turn.question()));
            historyMessages.add(new AssistantMessage(turn.answer()));
        }

        // Call Gemini: system prompt (with RAG context) + history + current question
        String answer = chatClient.prompt()
                .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
                .messages(historyMessages)
                .user(question)
                .call()
                .content();

        // Save this turn to the session history
        chatHistoryService.addTurn(sessionId, question, answer);

        List<String> chunks = relevantDocs.stream()
                .map(doc -> {
                    String content = doc.getText();
                    return content.substring(0, Math.min(content.length(), 200)) + "...";
                })
                .toList();

        return new QueryResponse(sessionId, answer, question, scope, chunks);
    }
}
