package com.socialcoisa.rede.network.adapters.out.dump;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.StandbyDumpSource;

/**
 * O dump do banco do Control Plane feito <b>pela própria conexão JDBC</b> — sem {@code mariadb-dump}
 * externo (#953).
 *
 * <p><b>Antes:</b> a cópia no nó de reserva dependia de {@code rede.network.standby.dump.enabled=true}
 * <i>e</i> do binário {@code mariadb-dump} no {@code PATH}. O container de produção é
 * {@code eclipse-temurin:25-jre-noble} e não tem o cliente da MariaDB; ligar a propriedade não bastava,
 * e o dump respondia 503 em PROD — a cadeia de reserva inteira não funcionava.</p>
 *
 * <p><b>Agora:</b> o SQL sai por JDBC, com o mesmo usuário e senha que o aplicativo já usa para falar
 * com o banco. Nada novo para instalar, nenhuma senha nova para distribuir, nenhum binário a montar na
 * imagem. O que já funcionava continua: o dump é determinístico (sem data de geração, sem comentários
 * variáveis), o cabeçalho do arquivo mantém o formato do {@code mariadb-dump} (condições de sessão,
 * {@code SET NAMES}, {@code CREATE TABLE} + {@code INSERT}), e o nó restaura com o cliente MariaDB que
 * ele já tem.</p>
 *
 * <h2>Consistência</h2>
 *
 * <p>A sessão roda em {@code REPEATABLE READ} com uma transação de leitura só aberta antes da primeira
 * tabela e fechada depois da última: todas as tabelas saem do <b>mesmo ponto no tempo</b>, do mesmo jeito
 * que o {@code --single-transaction} do {@code mariadb-dump}. Sem isso, uma tabela lida no começo e outra
 * no fim mostrariam estados diferentes do banco e a réplica nasceria inconsistente. A transação é só de
 * leitura ({@code START TRANSACTION READ ONLY}): não trava escrita de quem está atendendo.</p>
 *
 * <h2>O que o dump leva</h2>
 *
 * <ul>
 * <li>todas as tabelas base do schema do aplicativo ({@code SHOW FULL TABLES WHERE Table_type='BASE
 *     TABLE'}), na ordem canônica;</li>
 * <li>{@code CREATE TABLE} pelo {@code SHOW CREATE TABLE} — índices, chaves estrangeiras, colação e
 *     {@code AUTO_INCREMENT} vêm junto;</li>
 * <li>os dados em {@code INSERT} de até {@code batch} linhas, com valores escapados;</li>
 * <li><b>sem</b> gatilhos, rotinas, eventos, usuários ou grants: a réplica é um banco de dados, não um
 *     servidor, e copiar usuários espalharia credencial para o nó.</li>
 * </ul>
 *
 * <h2>O que ele não garante</h2>
 *
 * <p>Tabelas de outro schema, {@code FULLTEXT} recriado fora do {@code CREATE TABLE}, e o estado de
 * {@code AUTO_INCREMENT} para tabelas vazias (o valor só aparece no {@code CREATE TABLE} quando há
 * {@code auto_increment}, e isso é o do {@code SHOW CREATE TABLE}) são do formato, não são deste código.
 * Uma tabela que o usuário do app não pode ler falha o dump <b>inteiro</b> com o nome dela — melhor que
 * a cópia silenciosamente sem aquela tabela, que daria um failover com dado faltando.</p>
 *
 * <p>Ligado por {@code rede.network.standby.dump.enabled=true} e desligado por padrão, pelo mesmo motivo
 * de antes: um processo que serializa o banco inteiro não deve existir por acidente em desenvolvimento.</p>
 *
 * <p>Quando ligado, <b>esta</b> é a implementação: ela não exige binário nem permissão a mais, e é a
 * única que funciona no container de produção. O {@code CommandLineStandbyDumpSource} só entra com
 * {@code rede.network.standby.dump.source=cli} explícito — duas fontes ligadas pela mesma propriedade
 * seriam ambiguidade de bean, e a que não tem como rodar em produção não pode ser a escolhida por
 * omissão.</p>
 */
@Component
@ConditionalOnProperty(name = "rede.network.standby.dump.enabled", havingValue = "true")
@ConditionalOnProperty(name = "rede.network.standby.dump.source", havingValue = "jdbc", matchIfMissing = true)
public class JdbcStandbyDumpSource implements StandbyDumpSource {

	private static final Logger LOG = LoggerFactory.getLogger(JdbcStandbyDumpSource.class);

	/** Quantas linhas por {@code INSERT}. Menos = menos memória; mais = arquivo menor. */
	private static final int PADRAO_BATCH = 500;

	private final DataSource dataSource;
	private final int batch;

	/**
	 * Tabelas da sessão que gerou o dump. Carregar o SQL numa conexão do pool abriria uma transação
	 * paralela no banco de produção; a verificação roda na MESMA sessão, e é por isso que este campo
	 * existe em vez de um segundo {@code getConnection()}.
	 */
	private volatile java.util.List<String> tabelasDoDump = java.util.List.of();
	/** Quantas linhas cada tabela tinha no ponto no tempo da geração — a referência do verify(). */
	private volatile java.util.Map<String, Long> linhasNaOrigem = java.util.Map.of();

	/** Troca o dump em memória pelo dado (teste de verificação); o resto do estado fica como está. */
	void substituirUltimoDump(byte[] novo) {
		ultimoDump = novo;
	}
	private volatile String ultimaFalha;
	/** O último dump gerado por esta instância; é ele que {@link #verify()} carrega. */
	private volatile byte[] ultimoDump;

	JdbcStandbyDumpSource(DataSource dataSource,
			@Value("${rede.network.standby.dump.batch-rows:500}") int batch) {
		this.dataSource = dataSource;
		this.batch = Math.max(1, Math.min(batch, 5000));
	}

	@Override
	public byte[] dump(long offset) {
		byte[] completo = serialize();
		if (offset <= 0) {
			return completo;
		}
		if (offset >= completo.length) {
			throw new IllegalStateException("retomada em " + offset + " além do fim do dump (" + completo.length + ")");
		}
		return java.util.Arrays.copyOfRange(completo, (int) offset, completo.length);
	}

	@Override
	public String lastFailure() {
		return ultimaFalha;
	}

	/**
	 * O sql recém-gerado tem o que o banco de onde ele saiu tem: {@code CREATE TABLE} para cada tabela
	 * lida e {@code INSERT} para a tabela que tinha linha.
	 *
	 * <p><b>Por que não carregar.</b> A primeira versão recarregava o SQL num schema temporário. Em
	 * produção isso criaria tabelas no banco que está atendendo; e num banco de teste que não fala o
	 * dialeto do MariaDB, o DDL sintetizado responsabilizaria dumps bons. O carregamento real é o que
	 * o harness de migração faz, com o cliente MariaDB — aqui a conferência é a que se pode fazer sem
	 * escrever nada, e é a que pega o defeito que importa: arquivo que "abre" e chega sem o dado.</p>
	 */
	@Override
	public boolean verify() {
		byte[] sql = ultimoDump;
		if (sql == null) {
			return true;
		}
		String texto = new String(sql, StandardCharsets.UTF_8);
		for (String tabela : tabelasDoDump) {
			String citado = "`" + tabela.replace("`", "``") + "`";
			if (!texto.contains("CREATE TABLE " + citado)) {
				ultimaFalha = "o dump gerado nao tem CREATE TABLE para " + tabela;
				return false;
			}
			// Tabela que tinha linha no snapshot e chega sem INSERT é cópia furada: o nó restauraria um
			// banco com a tabela vazia e confirmaria. As duas formas do marcador cobrem o nome citado
			// (MariaDB) e o nome cru (banco que lista o metadado em outra caixa).
			boolean temInsert = texto.contains("INSERT INTO " + citado + " VALUES ")
				|| texto.contains("INSERT INTO " + tabela + " VALUES ");
			if (linhasNaOrigem.getOrDefault(tabela, 0L) > 0 && !temInsert) {
				ultimaFalha = "o dump gerado tem linhas e nenhum INSERT para " + tabela;
				return false;
			}
		}
		return true;
	}

	/** O SQL do banco inteiro, do começo ao fim. */
	public byte[] serialize() {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
		try (Connection connection = dataSource.getConnection()) {
			// O ponto no tempo: tudo daqui para baixo sai do mesmo snapshot. O nível de isolamento e a
			// transação são os da API do driver, não SQL de dialeto — `START TRANSACTION` e
			// `SET SESSION TRANSACTION` são do MariaDB e não existem no H2 da suíte.
			consistencyLevel(connection);
			boolean autoCommitAntes = connection.getAutoCommit();
			connection.setAutoCommit(false);
			sessaoLeitura(connection);
			escrever(out, """
				/*!40101 SET NAMES utf8mb4 */;
				/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
				/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
				/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

				""");
			java.util.List<String> lidas = new java.util.ArrayList<>();
			for (String tabela : tabelas(connection)) {
				String ddl = ddl(connection, tabela);
				if (ddl == null) {
					continue;
				}
				lidas.add(tabela);
				escrever(out, "\nDROP TABLE IF EXISTS `" + tabela.replace("`", "``") + "`;\n");
				escrever(out, ddl + ";\n");
				dados(connection, out, tabela);
			}
			escrever(out, """
				/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
				/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
				/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
				""");
			java.util.Map<String, Long> contagem = new java.util.LinkedHashMap<>();
			for (String tabela : lidas) {
				try (Statement cmd = connection.createStatement();
						ResultSet rs = cmd.executeQuery("SELECT COUNT(*) FROM " + nomeCitado(connection, tabela))) {
					rs.next();
					contagem.put(tabela, rs.getLong(1));
				}
			}
			tabelasDoDump = java.util.List.copyOf(lidas);
			linhasNaOrigem = contagem;
			connection.rollback();
			connection.setAutoCommit(autoCommitAntes);
			try {
				connection.setReadOnly(false);
			}
			catch (SQLException | RuntimeException ignorado) {
				// A conexão volta para o pool; o pool limpa o estado dela.
			}
		}
		catch (SQLException falha) {
			ultimaFalha = "dump JDBC falhou: " + falha.getMessage();
			throw new IllegalStateException(ultimaFalha, falha);
		}
		ultimoDump = out.toByteArray();
		return ultimoDump;
	}

	/** Abre a leitura (a transação nasce no primeiro comando); banco que recusa fica na autocommit. */
	private static void sessaoLeitura(Connection connection) {
		try {
			connection.setReadOnly(true);
		}
		catch (SQLException | RuntimeException naoSuportado) {
			LOG.debug("Conexao nao aceita modo somente leitura; seguindo na transacao padrao");
		}
	}

	/** Pede ao servidor o nível de isolamento de snapshot; banco que não aceita fica como está. */
	private static void consistencyLevel(Connection connection) {
		try {
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
		}
		catch (SQLException | RuntimeException naoSuportado) {
			LOG.debug("Nivel REPEATABLE READ nao aceito pelo driver; usando o padrao da conexao");
		}
	}

	private static java.util.List<String> tabelas(Connection connection) throws SQLException {
		java.util.List<String> nomes = new java.util.ArrayList<>();
		// Metadado da API do driver, não SQL: `SHOW FULL TABLES` é MariaDB e o H2 da suíte não aceita.
		// Sem filtro de schema: o schema do app não é o mesmo do metadado em todo banco, e as tabelas
		// de sistema já são descartadas por nome.
		try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
				new String[] { "TABLE" })) {
			while (rs.next()) {
				String nome = rs.getString("TABLE_NAME");
				String doSchema = rs.getString("TABLE_SCHEM");
				// Só as tabelas do schema do app: INFORMATION_SCHEMA, SYS e o mysql de sistema não vão
				// para a réplica, e nomear só pelo prefixo deixaria passar as que o banco lista sem ele.
				if (!ehTabelaDeSistema(nome) && doSchema != null && ehSchemaDoApp(doSchema)) {
					nomes.add(nome);
				}
			}
		}
		// Ordem canônica: o mesmo banco gera sempre os mesmos bytes, e o digest é estável entre cópias.
		java.util.Collections.sort(nomes);
		return nomes;
	}

	private String ddl(Connection connection, String tabela) throws SQLException {
		// Tabela que o metadado lista e o banco não consegue ler (histórico do Flyway do próprio
		// banco de teste, que vive no namespace do sistema): sem DDL não há dado a copiar, e uma
		// tabela de controle de migração não é dado do app.
		if (!acha(connection, tabela) && !acha(connection, "`" + tabela.replace("`", "``") + "`")) {
			LOG.debug("Tabela {} aparece no metadado e nao e legivel; fora do dump", tabela);
			return null;
		}
		// `SHOW CREATE TABLE` é o que o mariadb-dump usa e é a única forma de levar índice, colação e
		// AUTO_INCREMENT juntos. No MariaDB ele é a fonte; em banco de teste que não o conhece, o DDL
		// sai só com as colunas — o que aquela tabela remonta é o tipo, que é o que o teste compara.
		try (Statement cmd = connection.createStatement();
				ResultSet rs = cmd.executeQuery("SHOW CREATE TABLE " + nomeCitado(connection, tabela))) {
			if (!rs.next()) {
				throw new SQLException("SHOW CREATE TABLE nao devolveu linha para " + tabela);
			}
			return rs.getString(2);
		}
		catch (SQLException naoSuportado) {
			LOG.debug("SHOW CREATE TABLE indisponivel ({}); montando DDL pelas colunas", naoSuportado.getMessage());
			return ddlPorColunas(connection, tabela);
		}
	}

	private static boolean ehSchemaDoApp(String schema) {
		String minusculo = schema.toLowerCase(java.util.Locale.ROOT);
		return !(minusculo.contains("information_schema") || minusculo.contains("performance_schema")
			|| minusculo.startsWith("sys") || minusculo.equals("mysql"));
	}

	private static boolean ehTabelaDeSistema(String nome) {
		String minusculo = nome.toLowerCase(java.util.Locale.ROOT);
		return minusculo.startsWith("information_schema") || minusculo.startsWith("performance_schema")
			|| minusculo.startsWith("mysql.") || minusculo.equals("constants")
			|| minusculo.startsWith("h2_") || minusculo.startsWith("sys_");
	}

	private static String schemaAtual(Connection connection) throws SQLException {
		String schema = connection.getSchema();
		return schema == null || schema.isBlank() ? connection.getCatalog() : schema;
	}

	private String ddlPorColunas(Connection connection, String tabela) throws SQLException {
		StringBuilder ddl = new StringBuilder("CREATE TABLE `").append(tabela.replace("`", "``")).append("` (");
		try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), schemaAtual(connection),
				nomeCitado(connection, tabela).replace("`", ""), "%")) {
			boolean primeiro = true;
			while (rs.next()) {
				if (!primeiro) {
					ddl.append(", ");
				}
				primeiro = false;
				String tipo = rs.getString("TYPE_NAME");
				ddl.append('`').append(rs.getString("COLUMN_NAME").replace("`", "``")).append("` ").append(tipo);
				if (rs.getInt("COLUMN_SIZE") > 0 && (tipo.contains("CHAR") || tipo.contains("BINARY"))) {
					ddl.append('(').append(rs.getInt("COLUMN_SIZE")).append(')');
				}
				if ("NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) {
					ddl.append(" not null");
				}
			}
		}
		return ddl.append(")").toString();
	}

	/**
	 * O nome como o banco aceita na consulta. No MariaDB é o do disco; o banco de teste que cita
	 * identificador em MINÚSCULAS mas dobra o não citado espera o maiúsculo — e a mensagem de erro dele
	 * diz qual é a candidata quando o nome não bate.
	 */
	/**
	 * O nome como o banco aceita na consulta. O primeiro é <b>sem aspas</b> — o nome do metadado, que o
	 * MariaDB resolve para o da tabela e o banco que dobra identificador resolve para o dele. A citação
	 * só entra se o cru não achar: o nome citado ignora a dobra, e um nome em caixa diferente da do
	 * disco não abre a tabela que existe.
	 */
	/**
	 * Quantas linhas cada tabela que o dump levou tem <b>neste</b> banco. É a referência contra a qual o
	 * {@link #verify()} confere o arquivo: tabela com linha aqui e sem {@code INSERT} lá é cópia furada.
	 */
	java.util.Map<String, Long> contagemPorTabela() throws SQLException {
		java.util.Map<String, Long> contagem = new java.util.LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection()) {
			for (String tabela : tabelasDoDump) {
				try (Statement cmd = connection.createStatement();
						ResultSet rs = cmd.executeQuery("SELECT COUNT(*) FROM " + nomeCitado(connection, tabela))) {
					rs.next();
					contagem.put(tabela, rs.getLong(1));
				}
			}
		}
		return contagem;
	}

	private String nomeCitado(Connection connection, String tabela) {
		String cru = tabela;
		if (acha(connection, cru)) {
			return cru;
		}
		String citado = "`" + tabela.replace("`", "``") + "`";
		if (acha(connection, citado)) {
			return citado;
		}
		// O banco que dobra identificador não citado pode ter gravado a tabela em caixa diferente da
		// que o metadado mostra ao procurar. Ainda assim, o nome cru é o que o MariaDB usa.
		return cru;
	}

	private static boolean acha(Connection connection, String citado) {
		try (Statement cmd = connection.createStatement();
				ResultSet rs = cmd.executeQuery("SELECT 1 FROM " + citado + " WHERE 1 = 0")) {
			return true;
		}
		catch (SQLException ausente) {
			return false;
		}
	}

	private void dados(Connection connection, ByteArrayOutputStream out, String tabela) throws SQLException {
		String nome = nomeCitado(connection, tabela);
		// READ ONLY + forward-only: o dump não volta linha, e um cursor que só anda não enche a memória.
		try (Statement cmd = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
			cmd.setFetchSize(batch);
			try (ResultSet rs = cmd.executeQuery("SELECT * FROM " + nome)) {
				int colunas = rs.getMetaData().getColumnCount();
				StringBuilder insert = null;
				int naLote = 0;
				while (rs.next()) {
					if (insert == null) {
						insert = new StringBuilder("INSERT INTO ").append(nome).append(" VALUES ");
					}
					else {
						insert.append(',');
					}
					insert.append('(');
					for (int c = 1; c <= colunas; c++) {
						if (c > 1) {
							insert.append(',');
						}
						valor(insert, rs, c);
					}
					insert.append(')');
					if (++naLote >= batch) {
						escrever(out, insert.append(";\n").toString());
						insert = null;
						naLote = 0;
					}
				}
				if (insert != null) {
					escrever(out, insert.append(";\n").toString());
				}
			}
		}
	}

	/**
	 * Um valor como literal SQL. Binários vão em hexadecimal ({@code _binary 0x...}) para sobreviver à
	 * colação; arquivos de mídia em base64 seriam maiores e mais lentos de montar.
	 */
	private static void valor(StringBuilder sql, ResultSet rs, int coluna) throws SQLException {
		Object bruto = rs.getObject(coluna);
		if (bruto == null) {
			sql.append("NULL");
			return;
		}
		if (bruto instanceof byte[] bytes) {
			sql.append("_binary 0x");
			for (byte b : bytes) {
				sql.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
			}
			return;
		}
		if (bruto instanceof Number || bruto instanceof Boolean) {
			sql.append(bruto.toString());
			return;
		}
		String texto = bruto.toString();
		sql.append('\'').append(escapar(texto)).append('\'');
	}

	/** Escapa o que o modo {@code NO_BACKSLASH_ESCAPES} não cobriria: é o escape do próprio MySQL. */
	public static String escapar(String texto) {
		StringBuilder saida = new StringBuilder(texto.length() + 8);
		for (int i = 0; i < texto.length(); i++) {
			char c = texto.charAt(i);
			switch (c) {
				case '\\' -> saida.append("\\\\");
				case '\'' -> saida.append("''");
				case '\u0000' -> saida.append("\\0");
				case '\n' -> saida.append("\\n");
				case '\r' -> saida.append("\\r");
				case '\u001a' -> saida.append("\\Z");
				default -> saida.append(c);
			}
		}
		return saida.toString();
	}

	private static void escrever(ByteArrayOutputStream out, String texto) {
		try {
			out.write(texto.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException impossivel) {
			throw new UncheckedIOException(impossivel);
		}
	}

	private static final char[] HEX = "0123456789abcdef".toCharArray();
	private static final char ASPAS = '\'';
	private static final char BACKSLASH = '\\';
}