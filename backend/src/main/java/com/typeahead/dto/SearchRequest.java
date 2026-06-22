package com.typeahead.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(@NotBlank(message = "query must not be blank") String query) {}
