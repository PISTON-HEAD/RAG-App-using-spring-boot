package com.ragapp.document;

import com.ragapp.dto.DocumentInfo;
import com.ragapp.dto.DocumentUploadResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private final SimpleVectorStore vectorStore;

    @Value("${app.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    // Tracks uploaded documents: documentId -> DocumentInfo
    private final Map<String, DocumentInfo> documentStore = new ConcurrentHashMap<>();

    public DocumentService(SimpleVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public DocumentUploadResponse ingestDocument(MultipartFile file) throws IOException {
        String documentId = UUID.randomUUID().toString();
        String filename = file.getOriginalFilename();

        // 1. Parse document using Tika
        TikaDocumentReader reader = new TikaDocumentReader(
                new InputStreamResource(file.getInputStream())
        );
        List<Document> rawDocuments = reader.get();

        // 2. Split into chunks
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);
        List<Document> chunks = splitter.apply(rawDocuments);

        // 3. Tag each chunk with documentId for filtering
        chunks.forEach(chunk ->
                chunk.getMetadata().put("documentId", documentId)
        );

        // 4. Embed and store in vector store
        vectorStore.add(chunks);

        // 5. Track document
        DocumentInfo info = new DocumentInfo(documentId, filename, chunks.size());
        documentStore.put(documentId, info);

        return new DocumentUploadResponse(
                documentId,
                filename,
                chunks.size(),
                "Document indexed successfully"
        );
    }

    public List<DocumentInfo> listDocuments() {
        return List.copyOf(documentStore.values());
    }

    public boolean documentExists(String documentId) {
        return documentStore.containsKey(documentId);
    }
}
