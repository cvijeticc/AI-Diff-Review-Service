package com.cvijeticc.diffreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DiffReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiffReviewApplication.class, args);
    }
}
