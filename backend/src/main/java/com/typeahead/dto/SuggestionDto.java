package com.typeahead.dto;

/**
 * Single suggestion returned by GET /suggest.
 * `score` is the all-time count in basic mode, or the blended
 * recency-aware score in enhanced mode - see TrendingService.
 */
public record SuggestionDto(String query, long count, double score) {
}
