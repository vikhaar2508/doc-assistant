package com.confluence.docassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocAssistantApplication.class, args);
    }
}
