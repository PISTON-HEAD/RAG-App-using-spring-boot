package com.ragapp.query;

import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/documents")
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
