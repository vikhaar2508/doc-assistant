package com.confluence.docassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Vision AI (LLaVA) diagram extractor.
 * Bound from application.yml under the 'vision' prefix.
 */
@Component
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    private boolean enabled = true;
    private String ollamaBaseUrl = "http://localhost:11434";
    private String model = "llava";
    private String prompt = "Describe this technical diagram in plain text. Include all systems, flows, and relationships.";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getOllamaBaseUrl() { return ollamaBaseUrl; }
    public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
