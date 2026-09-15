package com.bridgepay.application;

import org.springframework.boot.SpringApplication;

public class TestApplicationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ApplicationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
