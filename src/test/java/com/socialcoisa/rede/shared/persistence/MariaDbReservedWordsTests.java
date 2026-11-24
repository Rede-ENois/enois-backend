package com.socialcoisa.rede.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Coluna não pode se chamar como uma PALAVRA RESERVADA do MariaDB — e o app já quebrou por isso
 * duas vezes:
 *
 * - a V140 criava `moderation_signal_weights` com a coluna `signal` (reservada): o `CREATE TABLE`
 *   devolvia 1064 e o pod da produção não subia. O H2 dos testes aceitava, então a suíte passava
 *   e o banco real não;
 * - a coluna `read` das notificações (também reservada) fazia o Hibernate gerar
 *   `update notifications set ..., read=?, ...` — 500 em todo insert/update de notificação, ou
 *   seja, comentar um post quebrava. A V170 renomeou para `is_read`.
 *
 * Por que o teste é necessário: o **H2 dos testes aceita identificador reservado** e a **QA cita
 * identificadores** (`globally_quoted_identifiers=true`), então nos dois lugares o bug passa
 * batido. Só a produção, que não cita, quebra — e a produção é justamente onde a sessão de
 * webhook/cron cai.
 *
 * A regra: no DDL, o nome reservado tem de vir **citado** (entre crases); no mapeamento JPA, o
 * nome da coluna não pode ser reservado (o Hibernate monta o SQL e não cita).
 */
@Tag("critico")
class MariaDbReservedWordsTests {

	private static final Path MIGRACOES = Path.of("src", "main", "resources", "db", "migration");
	private static final Path JAVA = Path.of("src", "main", "java");

	/** Tipo de coluna: o que vem DEPOIS do nome (para nao confundir `if not exists` com a coluna). */
	private static final String TIPOS = "(BIGINT|INT|INTEGER|VARCHAR|TEXT|BOOLEAN|BOOL|TIMESTAMP|DATETIME"
		+ "|DATE|DECIMAL|NUMERIC|DOUBLE|JSON|LONGTEXT|CLOB|BLOB|TINYINT|SMALLINT|CHAR|VARBINARY|LONGBLOB)";

	/** Coluna declarada na migration: `add column x`, `modify x`, `change x` (com `if not exists`). */
	private static final Pattern COLUNA_ALTERADA = Pattern.compile(
		"\\b(?:ADD|MODIFY|CHANGE)\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(`?)(\\w+)\\1\\s+" + TIPOS,
		Pattern.CASE_INSENSITIVE);

	/** Coluna dentro de `create table (...)`: a linha com `nome TIPO`. */
	private static final Pattern COLUNA_CREATE = Pattern.compile(
		"^\\s*(`?)(\\w+)\\1\\s+" + TIPOS + "\\b", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	/** `primary key (x)` / `unique key name (x)` — a coluna entre parenteses. */
	private static final Pattern COLUNA_INDICE = Pattern.compile(
		"(?:PRIMARY KEY|UNIQUE KEY|UNIQUE INDEX|KEY|INDEX)\\s*\\(\\s*(`?)(\\w+)\\1", Pattern.CASE_INSENSITIVE);

	/** Coluna do mapeamento JPA: `@Column(name = "x")` ou, sem ele, o nome do campo. */
	private static final Pattern COLUNA_ENTITY = Pattern.compile(
		"@Column\\((?:[^)]*?)\\)\\s*(?:private|protected|public)?\\s*[\\w<>,\\[\\] .]+?\\s+(\\w+)\\s*;",
		Pattern.DOTALL);

	@Test
	void nenhumaMigrationUsaColunaReservadaSemCitar() throws IOException {
		Set<String> reservadas = reservadas();
		List<String> problemas = new ArrayList<>();

		try (Stream<Path> migrations = Files.list(MIGRACOES)) {
			for (Path migration : migrations.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
				String sql = Files.readString(migration);
				String semComentario = sql.replaceAll("(?m)--[^\\n]*", "");
				for (Matcher matcher : List.of(
					COLUNA_ALTERADA.matcher(semComentario),
					COLUNA_CREATE.matcher(semComentario),
					COLUNA_INDICE.matcher(semComentario))) {
					while (matcher.find()) {
						// grupo 1 = crase de abertura (vazia = sem crase), grupo 2 = nome
						String crase = matcher.group(1);
						String coluna = matcher.group(2);
						if (crase.isEmpty() && reservadas.contains(coluna.toUpperCase(Locale.ROOT))) {
							problemas.add(migration.getFileName() + ": coluna `" + coluna
								+ "` e palavra reservada do MariaDB e esta sem crase");
						}
					}
				}
			}
		}

		assertThat(problemas).as("coluna com palavra reservada do MariaDB (sem crase o DDL so quebra "
			+ "em MySQL/MariaDB; no H2 dos testes passa)").isEmpty();
	}

	@Test
	void nenhumaEntidadeMapeiaColunaReservada() throws IOException {
		Set<String> reservadas = reservadas();
		List<String> problemas = new ArrayList<>();

		try (Stream<Path> arquivos = Files.walk(JAVA)) {
			for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
				String fonte = Files.readString(arquivo);
				if (!fonte.contains("@Entity")) {
					continue;
				}
				Matcher matcher = COLUNA_ENTITY.matcher(fonte);
				while (matcher.find()) {
					String campo = matcher.group(1);
					// a coluna e o `name` do @Column; sem ele, o nome do campo (o Hibernate usa
					// a estrategia de nomes: camelCase vira snake_case)
					String coluna = colunaDeclarada(fonte, matcher.start(), campo);
					if (reservadas.contains(coluna.toUpperCase(Locale.ROOT))) {
						problemas.add(arquivo.getFileName() + ": coluna `" + coluna
							+ "` (campo `" + campo + "`) e palavra reservada do MariaDB — o Hibernate "
							+ "nao cita identificadores em producao");
					}
				}
			}
		}

		assertThat(problemas).as("entidade JPA com coluna de palavra reservada (o H2 aceita e a QA "
			+ "cita identificadores: so a producao quebraria)").isEmpty();
	}

	/** O `name` do `@Column` da declaracao; sem `@Column`, o nome do campo em snake_case. */
	private String colunaDeclarada(String fonte, int posicaoDoCampo, String campo) {
		int inicio = fonte.lastIndexOf("@Column", posicaoDoCampo);
		if (inicio >= 0) {
			int fim = fonte.indexOf(')', inicio);
			Matcher nome = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"").matcher(fonte.substring(inicio, fim));
			if (nome.find()) {
				return nome.group(1);
			}
		}
		return camelParaSnake(campo);
	}

	private String camelParaSnake(String nome) {
		return nome.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
	}

	/** As palavras reservadas: lista no classpath, extraida do proprio motor. */
	private Set<String> reservadas() throws IOException {
		Set<String> palavras = new LinkedHashSet<>();
		try (InputStream entrada = getClass().getClassLoader()
			.getResourceAsStream("mariadb-reserved-words.txt")) {
			if (entrada == null) {
				throw new IOException("recurso mariadb-reserved-words.txt nao esta no classpath");
			}
			for (String linha : new String(entrada.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				String palavra = linha.trim();
				if (!palavra.isEmpty() && !palavra.startsWith("#")) {
					palavras.add(palavra.toUpperCase(Locale.ROOT));
				}
			}
		}
		return palavras;
	}
}
