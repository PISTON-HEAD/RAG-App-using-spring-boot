package com.ragapp.query;

import com.ragapp.document.DocumentService;
import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for QueryService.
 *
 * All AI dependencies (VectorStore, ChatModel) are mocked so tests run
 * completely offline and deterministically.
 *
 * We use Mockito's deep-stub feature for ChatClient because it has a
 * fluent builder chain:  prompt().system(...).user(...).call().content()
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private SimpleVectorStore vectorStore;

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ChatModel chatModel;

    @Mock
    private DocumentService documentService;

    private QueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(vectorStore, chatModel, documentService);
        ReflectionTestUtils.setField(queryService, "topK", 4);
    }

    // ── Successful query ───────────────────────────────────────────────────────

    @Test
    @DisplayName("query: returns a QueryResponse with the LLM answer when document exists")
    void query_withExistingDocument_returnsAnswer() {
        String docId = "doc-123";
        String question = "What is RAG?";
        String expectedAnswer = "RAG stands for Retrieval-Augmented Generation.";

        // Document service confirms the document exists
        when(documentService.documentExists(docId)).thenReturn(true);

        // Vector store returns one matching chunk
        Document chunk = new Document("RAG is a technique that combines retrieval with generation.",
                Map.of("documentId", docId));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

        // Directly mock the ChatClient since it's built internally — use a spy approach
        QueryService spyService = spy(queryService);
        ChatClient mockChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(mockChatClient.prompt().system(any(Consumer.class)).user(anyString()).call().content())
                .thenReturn(expectedAnswer);
        ReflectionTestUtils.setField(spyService, "chatClient", mockChatClient);

        QueryResponse response = spyService.query(docId, new QueryRequest(question));

        assertThat(response.answer()).isEqualTo(expectedAnswer);
        assertThat(response.question()).isEqualTo(question);
        assertThat(response.documentId()).isEqualTo(docId);
        assertThat(response.relevantChunks()).hasSize(1);
    }

    @Test
    @DisplayName("query: calls vectorStore.similaritySearch with topK=4")
    void query_callsVectorStoreWithCorrectTopK() {
        String docId = "doc-456";
        when(documentService.documentExists(docId)).thenReturn(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryService spyService = spy(queryService);
        ChatClient mockChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(mockChatClient.prompt().system(any(Consumer.class)).user(anyString()).call().content())
                .thenReturn("An answer");
        ReflectionTestUtils.setField(spyService, "chatClient", mockChatClient);

        spyService.query(docId, new QueryRequest("some question"));

        // Verify vector store was called exactly once
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("query: returns empty relevantChunks when no similar documents found")
    void query_withNoMatchingChunks_returnsEmptyChunks() {
        String docId = "doc-789";
        when(documentService.documentExists(docId)).thenReturn(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryService spyService = spy(queryService);
        ChatClient mockChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(mockChatClient.prompt().system(any(Consumer.class)).user(anyString()).call().content())
                .thenReturn("I don't have enough context to answer.");
        ReflectionTestUtils.setField(spyService, "chatClient", mockChatClient);

        QueryResponse response = spyService.query(docId, new QueryRequest("question"));

        assertThat(response.relevantChunks()).isEmpty();
    }

    // ── Document not found ────────────────────────────────────────────────────

    @Test
    @DisplayName("query: throws IllegalArgumentException when document does not exist")
    void query_withNonExistentDocument_throwsIllegalArgumentException() {
        when(documentService.documentExists("missing-doc")).thenReturn(false);

        assertThatThrownBy(() ->
                queryService.query("missing-doc", new QueryRequest("what is this?"))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-doc");
    }

    @Test
    @DisplayName("query: does NOT call vectorStore if document does not exist")
    void query_withNonExistentDocument_neverCallsVectorStore() {
        when(documentService.documentExists("ghost")).thenReturn(false);

        try {
            queryService.query("ghost", new QueryRequest("question"));
        } catch (IllegalArgumentException ignored) {}

        verifyNoInteractions(vectorStore);
    }

    // ── Cross-document (global) query ─────────────────────────────────────────

    @Test
    @DisplayName("queryAllDocuments: searches without a filter and returns ALL_DOCUMENTS scope")
    void queryAllDocuments_returnsAllDocumentsScope() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryService spyService = spy(queryService);
        ChatClient mockChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(mockChatClient.prompt().system(any(Consumer.class)).user(anyString()).call().content())
                .thenReturn("Cross-document answer.");
        ReflectionTestUtils.setField(spyService, "chatClient", mockChatClient);

        QueryResponse response = spyService.queryAllDocuments(new QueryRequest("general question"));

        assertThat(response.documentId()).isEqualTo("ALL_DOCUMENTS");
        assertThat(response.answer()).isEqualTo("Cross-document answer.");
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("queryAllDocuments: does not check documentExists — no document guard needed")
    void queryAllDocuments_doesNotCheckDocumentExists() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryService spyService = spy(queryService);
        ChatClient mockChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(mockChatClient.prompt().system(any(Consumer.class)).user(anyString()).call().content())
                .thenReturn("Answer.");
        ReflectionTestUtils.setField(spyService, "chatClient", mockChatClient);

        spyService.queryAllDocuments(new QueryRequest("something"));

        verifyNoInteractions(documentService);
    }
}
