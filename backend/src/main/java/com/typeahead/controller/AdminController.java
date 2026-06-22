package com.typeahead.controller;

import com.typeahead.service.BatchWriterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final BatchWriterService batchWriterService;

    /**
     * GET /admin/batch-stats
     * Reports the write-reduction numbers the assignment's performance
     * report (section 12) asks for: total search requests received vs
     * actual Postgres writes performed.
     */
    @GetMapping("/admin/batch-stats")
    public BatchWriterService.BatchStats batchStats() {
        return batchWriterService.getStats();
    }
}
