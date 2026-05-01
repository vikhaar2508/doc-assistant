package com.confluence.docassistant.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Explicitly configures Anthropic as primary chat model
 * and forces the correct model name — bypasses Spring AI M6
 * hardcoded default of claude-3-5-sonnet-latest.
 */
@Configuration
public class AiConfig {

    @Value("${spring.ai.anthropic.api-key}")
    private String apiKey;

    @Bean
    @Primary
    public ChatModel primaryChatModel() {
        AnthropicApi api = new AnthropicApi(apiKey);
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(
                        org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                                .model("claude-haiku-4-5-20251001")
                                .temperature(0.7)
                                .maxTokens(4096)
                                .build()
                )
                .build();
    }
}