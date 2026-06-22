package com.typeahead.dto;

public record CacheDebugResponse(
        String prefix,
        String ownerNode,
        boolean hit,
        int ringVirtualNodeCount,
        int physicalNodeCount
) {}
