package com.josephinealinea.planner;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class PlannerApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlannerApiApplication.class, args);
    }
}
