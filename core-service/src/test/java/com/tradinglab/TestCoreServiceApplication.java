package com.tradinglab;

import org.springframework.boot.SpringApplication;

public class TestCoreServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CoreServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
