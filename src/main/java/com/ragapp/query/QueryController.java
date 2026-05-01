package com.ragapp.query;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/documents")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Query a specific document by its documentId.
     * Only chunks from that document are searched.
     */
    @PostMapping("/{documentId}/query")
    public ResponseEntity<QueryResponse> queryDocument(
            @PathVariable String documentId,
            @Valid @RequestBody QueryRequest request
    ) {
        QueryResponse response = queryService.query(documentId, request);
        return ResponseEntity.ok(response);
    }
}
