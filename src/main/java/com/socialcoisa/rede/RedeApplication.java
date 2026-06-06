package com.socialcoisa.rede;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RedeApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedeApplication.class, args);
	}

}
