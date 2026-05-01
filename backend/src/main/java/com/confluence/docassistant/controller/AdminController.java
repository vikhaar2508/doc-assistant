package com.confluence.docassistant.controller;

import com.confluence.docassistant.service.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final FeedbackService feedbackService;

    public AdminController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * GET /api/admin/dashboard
     * All dashboard data in one call.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(Map.of(
                "summary",          feedbackService.getSummary(),
                "topQuestions",     feedbackService.getTopQuestions(),
                "failingQuestions", feedbackService.getFailingQuestions(),
                "recentNegative",   feedbackService.getNegativeFeedback()
        ));
    }
}