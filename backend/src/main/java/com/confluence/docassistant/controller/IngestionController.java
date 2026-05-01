package com.confluence.docassistant.controller;

import com.confluence.docassistant.service.ConfluenceIngestionService;
import com.confluence.docassistant.service.IncrementalSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final ConfluenceIngestionService ingestionService;
    private final IncrementalSyncService     incrementalSyncService;

    public IngestionController(ConfluenceIngestionService ingestionService,
                                IncrementalSyncService incrementalSyncService) {
        this.ingestionService       = ingestionService;
        this.incrementalSyncService = incrementalSyncService;
    }

    @PostMapping("/confluence")
    public ResponseEntity<Map<String, Object>> ingestFull() {
        var result = ingestionService.ingestSpace();
        return ResponseEntity.ok(Map.of(
                "type",           "full",
                "status",         "success",
                "pagesFetched",   result.pagesFetched(),
                "documentsBuilt", result.documentsBuilt(),
                "chunksStored",   result.chunksStored(),
                "summary",        result.summary()
        ));
    }

    @PostMapping("/confluence/incremental")
    public ResponseEntity<Map<String, Object>> ingestIncremental() {
        var result = incrementalSyncService.syncIncremental();
        return ResponseEntity.ok(Map.of(
                "type",    "incremental",
                "status",  "success",
                "added",   result.added(),
                "updated", result.updated(),
                "deleted", result.deleted(),
                "skipped", result.skipped(),
                "summary", result.summary()
        ));
    }

    @PostMapping("/confluence/{pageId}")
    public ResponseEntity<Map<String, Object>> ingestPage(@PathVariable String pageId) {
        var result = ingestionService.ingestPage(pageId);
        return ResponseEntity.ok(Map.of(
                "type",         "single",
                "status",       "success",
                "pageId",       pageId,
                "chunksStored", result.chunksStored(),
                "summary",      result.summary()
        ));
    }
}
