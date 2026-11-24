package com.socialcoisa.rede;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;

import org.junit.jupiter.api.Tag;
import jakarta.persistence.EntityManagerFactory;

/**
 * Boots against a database built only from the Flyway migrations and lets Hibernate validate every
 * entity against it: a table or column that exists in an entity but in no migration fails here.
 */
@Tag("critico")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RedeApplicationTests {

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void contextLoadsWithSchemaValidatedAgainstMigrations() {
		assertThatCode(entityManagerFactory::createEntityManager).doesNotThrowAnyException();
	}

	@Test
	void refusesToBootWithoutAnExplicitProfile() {
		assertThatThrownBy(() -> RedeApplication.requireExplicitProfile(new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void bootsWithAnExplicitProfile() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("prd");
		assertThatCode(() -> RedeApplication.requireExplicitProfile(environment)).doesNotThrowAnyException();
	}

}
