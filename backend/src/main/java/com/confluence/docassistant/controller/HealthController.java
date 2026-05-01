package com.confluence.docassistant.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final WebClient ollamaWebClient;

    public HealthController() {
        this.ollamaWebClient = WebClient.builder()
                .baseUrl("http://localhost:11434")
                .build();
    }

    @GetMapping("/ollama")
    public ResponseEntity<Map<String, Object>> ollamaHealth() {
        try {
            ollamaWebClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return ResponseEntity.ok(Map.of("status", "online"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "offline"));
        }
    }
}
