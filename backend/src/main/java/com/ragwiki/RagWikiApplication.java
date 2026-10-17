package com.ragwiki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagWikiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagWikiApplication.class, args);
    }
}
