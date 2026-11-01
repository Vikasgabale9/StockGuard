package com.vikas.stockguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@EnableScheduling
@SpringBootApplication
public class StockguardApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockguardApplication.class, args);
	}

}
