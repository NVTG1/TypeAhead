package com.typeahead.dto;

import java.util.List;

public record TrendingResponse(boolean rankingEnhanced, List<SuggestionDto> trending) {}
