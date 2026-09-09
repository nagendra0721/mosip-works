package io.mosip.idrepository.anonymousprofile;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.mosip.idrepository.anonymousprofile.helper.AnonymousProfileHelper;

@SpringBootApplication
public class AnonymousProfileRebuildApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnonymousProfileRebuildApplication.class, args);
	}

	@Bean
	CommandLineRunner run(AnonymousProfileHelper anonymousProfileHelper) {
		return args -> anonymousProfileHelper.rebuild();
	}

	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper();
	}
}
