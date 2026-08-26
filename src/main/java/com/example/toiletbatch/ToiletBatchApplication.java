package com.example.toiletbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ToiletBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToiletBatchApplication.class, args);
    }

}
