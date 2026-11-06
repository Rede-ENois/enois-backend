package com.socialcoisa.rede.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.assertj.core.api.SoftAssertions;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

/**
 * Every {@code @Lock(PESSIMISTIC_WRITE)} repository query must render SQL that the MariaDB of each
 * deployed profile accepts.
 *
 * <p>The H2 suite never sees the production dialect, so a dialect that does not match the server
 * slips through: with {@code MySQLDialect} Hibernate renders {@code for update of <alias>}, which
 * MariaDB rejects with a syntax error (every locked read answered 500 in production). This test boots
 * Hibernate with the dialect each profile declares, using the same {@code allow_jdbc_metadata_access=false}
 * as the application (so the dialect version is the one Hibernate assumes in production), and captures
 * the SQL of each locked query before it reaches a database.
 */
class PessimisticLockSqlTests {

	private static final String BASE_PACKAGE = "com.socialcoisa.rede";

	@ParameterizedTest
	@ValueSource(strings = {"application-prd.properties", "application-qa.properties", "application-hml.properties"})
	void lockedQueriesRenderMariaDbCompatibleSql(String profile) throws IOException {
		Properties properties = loadProfile(profile);
		String dialect = properties.getProperty("spring.jpa.properties.hibernate.dialect");
		assertThat(dialect).as("dialect of %s", profile).isNotBlank();

		List<LockedQuery> lockedQueries = lockedQueries();
		assertThat(lockedQueries).as("locked repository queries found by the scan").isNotEmpty();

		CapturingInspector inspector = new CapturingInspector();
		try (SessionFactory sessionFactory = sessionFactory(dialect, lockedQueries, inspector)) {
			SoftAssertions softly = new SoftAssertions();
			for (LockedQuery locked : lockedQueries) {
				String sql = render(sessionFactory, inspector, locked);
				softly.assertThat(sql)
					.as("%s with %s (%s)", locked, dialect, profile)
					.contains(" for update")
					.doesNotContain("for update of")
					.doesNotContain("for share of");
			}
			softly.assertAll();
		}
	}

	private static String render(SessionFactory sessionFactory, CapturingInspector inspector, LockedQuery locked) {
		inspector.captured = null;
		assertThatThrownBy(() -> sessionFactory.inTransaction(session -> {
			var query = session.createQuery(locked.jpql(), locked.entity());
			query.getParameters().forEach(parameter -> query.setParameter(parameter.getName(), "probe"));
			query.setLockMode(locked.lockMode()).list();
		})).isInstanceOf(SqlCaptured.class);
		assertThat(inspector.captured).as("SQL rendered for %s", locked).isNotNull();
		return inspector.captured.toLowerCase(Locale.ROOT);
	}

	private static SessionFactory sessionFactory(String dialect, List<LockedQuery> lockedQueries,
			StatementInspector inspector) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
			.applySetting(AvailableSettings.DIALECT, dialect)
			.applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, false)
			.applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:lock-sql;DB_CLOSE_DELAY=-1")
			.applySetting(AvailableSettings.STATEMENT_INSPECTOR, inspector)
			.build();
		MetadataSources sources = new MetadataSources(registry);
		lockedQueries.stream().map(LockedQuery::entity).distinct().forEach(sources::addAnnotatedClass);
		return sources.buildMetadata().buildSessionFactory();
	}

	private static List<LockedQuery> lockedQueries() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

		List<LockedQuery> found = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
			Class<?> repository = loadClass(definition.getBeanClassName());
			Class<?> entity = ResolvableType.forClass(repository).as(Repository.class).resolveGeneric(0);
			for (Method method : repository.getDeclaredMethods()) {
				Lock lock = method.getAnnotation(Lock.class);
				if (lock == null) {
					continue;
				}
				Query query = method.getAnnotation(Query.class);
				assertThat(query).as("%s.%s locks without an explicit @Query", repository.getSimpleName(),
					method.getName()).isNotNull();
				found.add(new LockedQuery(repository.getSimpleName() + "." + method.getName(), entity,
					query.value(), lock.value()));
			}
		}
		return found;
	}

	private static Class<?> loadClass(String className) {
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	private Properties loadProfile(String resource) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertThat(input).as("profile %s on the classpath", resource).isNotNull();
			properties.load(input);
		}
		return properties;
	}

	private record LockedQuery(String name, Class<?> entity, String jpql, LockModeType lockMode) {
		@Override
		public String toString() {
			return name;
		}
	}

	/** Records the SQL and aborts the execution: the statement never reaches the H2 connection. */
	private static final class CapturingInspector implements StatementInspector {
		private String captured;

		@Override
		public String inspect(String sql) {
			captured = sql;
			throw new SqlCaptured();
		}
	}

	private static final class SqlCaptured extends RuntimeException {
		SqlCaptured() {
			super("SQL captured", null, false, false);
		}
	}
}
