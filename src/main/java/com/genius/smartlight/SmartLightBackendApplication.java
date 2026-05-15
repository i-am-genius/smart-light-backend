package com.genius.smartlight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.genius.smartlight.dal.mysql")
@EnableScheduling
@SpringBootApplication
public class SmartLightBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartLightBackendApplication.class, args);
    }
}