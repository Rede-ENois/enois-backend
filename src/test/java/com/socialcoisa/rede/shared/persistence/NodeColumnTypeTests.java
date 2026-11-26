package com.socialcoisa.rede.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.assertj.core.api.SoftAssertions;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * O DDL que o Hibernate espera da entidade é o que a migração criou no MariaDB de produção.
 *
 * <p>A suíte roda no H2, e com o H2 o mapeamento aprovava tudo: o ciclo 159 pôs
 * {@code columnDefinition = "double"} numa coluna {@code BigDecimal} para o H2 aceitar a coluna da
 * V382, o MariaDB de produção lê a mesma anotação como {@code NUMERIC}, e o app não subiu na `qa`
 * (validação de schema). A correção do ciclo 159b tirou o {@code columnDefinition}, o H2 voltou a
 * recusar a coluna — e nenhum teste local pegava nenhum dos dois lados.</p>
 *
 * <p>Este teste é a régua de produção sem produção: gera o DDL com o dialect que os profiles
 * publicam e exige que as colunas da entidade nasçam com o tipo que a migração declara.</p>
 */
class NodeColumnTypeTests {

	private static final String ENTITY = "com.socialcoisa.rede.network.adapters.out.persistence.NetworkNodeJpaEntity";

	/** Propriedade da entidade que a V382 criou e o tipo físico que a migração declara. */
	private static final List<Esperada> COLUNAS = List.of(
		new Esperada("observedTemperatureC", "observed_temperature_c", "double"));

	@ParameterizedTest
	@ValueSource(strings = {"application-prd.properties", "application-qa.properties", "application-hml.properties"})
	void colunasDeMedidaNascemComOTipoDaMigracaoNoDialectDeProducao(String profile) throws IOException {
		String dialect = loadProfile(profile).getProperty("spring.jpa.properties.hibernate.dialect");
		assertThat(dialect).as("dialect of %s", profile).isNotBlank();

		SoftAssertions softly = new SoftAssertions();
		for (Esperada esperada : COLUNAS) {
			String tipo = tipoDe(metadata(dialect), esperada.propriedade());
			softly.assertThat(tipo)
				.as("%s no %s, profile %s: a migração declara %s", esperada.coluna(), dialect, profile,
					esperada.tipoFisico())
				.isEqualTo(esperada.tipoFisico());
		}
		softly.assertAll();
	}

	/**
	 * O tipo (sem largura) que o Hibernate emite para a coluna, no dialect de produção.
	 *
	 * <p>Lê o mapeamento da entidade como está no app: é ele que se está conferindo. Acesso a
	 * metadados JDBC desligado, como em produção.</p>
	 */
	private static String tipoDe(Metadata metadata, String propriedade) {
		org.hibernate.mapping.Property property = metadata.getEntityBinding(ENTITY).getPropertyClosure()
			.stream()
			.filter(candidate -> propriedade.equals(candidate.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("a entidade não mapeia " + propriedade
				+ ": o teste não pode aferir uma coluna que não existe"));
		return property.getColumns().get(0).getSqlType(metadata).replaceAll("\\(.*", "").trim()
			.toLowerCase(Locale.ROOT);
	}

	private static Metadata metadata(String dialect) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
			.applySetting(AvailableSettings.DIALECT, dialect)
			.applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, false)
			.build();
		try {
			MetadataSources sources = new MetadataSources(registry);
			sources.addAnnotatedClass(loadClass(ENTITY));
			return sources.buildMetadata();
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
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

	private record Esperada(String propriedade, String coluna, String tipoFisico) {
	}
}