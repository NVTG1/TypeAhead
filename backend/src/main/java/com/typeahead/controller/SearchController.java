package com.typeahead.controller;

import com.typeahead.dto.SearchRequest;
import com.typeahead.dto.SearchResponse;
import com.typeahead.service.BatchWriterService;
import com.typeahead.service.TrendingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SearchController {

    private final BatchWriterService batchWriterService;
    private final TrendingService trendingService;

    /**
     * POST /search
     * Dummy search endpoint. Returns immediately with a canned response;
     * the actual count update is buffered (see BatchWriterService) rather
     * than written synchronously, per assignment section 8.
     */
    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        String query = request.query().trim();
        batchWriterService.submit(query);
        trendingService.recordActivity(query);
        return new SearchResponse("Searched", query);
    }
}
