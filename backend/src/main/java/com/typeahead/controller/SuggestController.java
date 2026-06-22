package com.typeahead.controller;

import com.typeahead.dto.SuggestResponse;
import com.typeahead.dto.TrendingResponse;
import com.typeahead.service.SuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // demo-only; restrict in a real deployment
public class SuggestController {

    private final SuggestionService suggestionService;

    /**
     * GET /suggest?q=<prefix>&enhanced=true|false
     * `enhanced` defaults to false (assignment's basic 60%-mark behavior:
     * pure all-time-count ordering). Pass enhanced=true to get the
     * recency-aware ranking from section 7's +20% requirement.
     */
    @GetMapping("/suggest")
    public SuggestResponse suggest(
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(name = "enhanced", required = false, defaultValue = "false") boolean enhanced) {
        return suggestionService.suggest(q, enhanced);
    }

    @GetMapping("/trending")
    public TrendingResponse trending(
            @RequestParam(name = "enhanced", required = false, defaultValue = "true") boolean enhanced,
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit) {
        return new TrendingResponse(enhanced, suggestionService.trending(enhanced, limit));
    }
}
