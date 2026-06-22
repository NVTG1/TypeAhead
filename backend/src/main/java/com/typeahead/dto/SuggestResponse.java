package com.typeahead.dto;

import java.util.List;

public record SuggestResponse(
        String query,
        boolean rankingEnhanced,
        boolean cacheHit,
        String cacheNode,
        double latencyMs,
        List<SuggestionDto> suggestions
) {}
