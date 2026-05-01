package com.ragapp;

import org.springframework.ai.model.transformers.autoconfigure.TransformersEmbeddingModelAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {TransformersEmbeddingModelAutoConfiguration.class})
@EnableScheduling
public class SpringRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}
