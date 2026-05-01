package com.confluence.docassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Base64;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    private final ConfluenceProperties props;

    public AppConfig(ConfluenceProperties props) {
        this.props = props;
    }

    /**
     * SPA fallback - any unknown route returns index.html
     * so Angular handles its own routing client-side.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * Serve static Angular files from /static/
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    @Bean
    public WebClient confluenceWebClient() {
        String credentials = props.getUsername() + ":" + props.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Basic " + encoded)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
