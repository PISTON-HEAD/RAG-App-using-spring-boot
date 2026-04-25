package com.ragapp.query;

import com.ragapp.dto.QueryRequest;
import com.ragapp.dto.QueryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles cross-document queries — searches across ALL uploaded documents.
 *
 * Use this when you don't know which document has the answer,
 * or when you want the system to find the best match from everything uploaded.
 *
 * POST /query  { "question": "What are the payment terms?" }
 */
@RestController
@RequestMapping("/query")
public class GlobalQueryController {

    private final QueryService queryService;

    public GlobalQueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<QueryResponse> queryAllDocuments(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = queryService.queryAllDocuments(request);
        return ResponseEntity.ok(response);
    }
}
