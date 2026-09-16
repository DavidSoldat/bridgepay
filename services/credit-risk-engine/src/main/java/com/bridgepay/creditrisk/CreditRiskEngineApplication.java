package com.bridgepay.creditrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CreditRiskEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditRiskEngineApplication.class, args);
    }
}
