package com.confluence.docassistant.controller;

import com.confluence.docassistant.model.Feedback;
import com.confluence.docassistant.service.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * POST /api/feedback
     * Save 👍 or 👎 for an answer.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitFeedback(
            @RequestBody FeedbackRequest request) {

        feedbackService.save(new Feedback(
                request.conversationId(),
                request.question(),
                request.answer(),
                request.rating(),
                request.comment(),
                request.confidence(),
                null
        ));

        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /**
     * GET /api/feedback/summary
     * Overall stats — total, positive rate.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(feedbackService.getSummary());
    }

    /**
     * GET /api/feedback/negative
     * All thumbs down — what needs fixing.
     */
    @GetMapping("/negative")
    public ResponseEntity<List<Map<String, Object>>> getNegative() {
        return ResponseEntity.ok(feedbackService.getNegativeFeedback());
    }

    /**
     * GET /api/feedback/top-questions
     * Most asked questions.
     */
    @GetMapping("/top-questions")
    public ResponseEntity<List<Map<String, Object>>> getTopQuestions() {
        return ResponseEntity.ok(feedbackService.getTopQuestions());
    }

    /**
     * GET /api/feedback/failing
     * Questions that always get thumbs down — content gaps.
     */
    @GetMapping("/failing")
    public ResponseEntity<List<Map<String, Object>>> getFailingQuestions() {
        return ResponseEntity.ok(feedbackService.getFailingQuestions());
    }

    public record FeedbackRequest(
            String conversationId,
            String question,
            String answer,
            String rating,
            String comment,
            String confidence
    ) {}
}