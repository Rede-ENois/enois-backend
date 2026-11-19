package com.socialcoisa.rede.network.application;

/**
 * De onde sai o SQL do banco do Control Plane para a cópia no nó de reserva (V364).
 *
 * <p>A política de cópia ({@link StandbySyncService}) não conhece {@code mariadb-dump}: ela pede os
 * bytes. Em produção a implementação é o dump real, com {@code --skip-dump-date} para que duas
 * cópias do mesmo dado tenham o mesmo digest; em teste é um gerador determinístico, porque o que
 * precisa ser provado é quando copiar e o que vale como cópia completa — o formato do dump é do
 * {@code mariadb-dump}, não nosso.</p>
 */
public interface StandbyDumpSource {

	/**
	 * O dump a partir do byte {@code offset} (0 = desde o começo; &gt; 0 = retomada).
	 *
	 * @throws IllegalStateException quando o banco não pode ser lido agora — a cópia falha com
	 * motivo, e o nó não fica com um arquivo pela metade parecendo completo.
	 */
	byte[] dump(long offset);

	/**
	 * "Não consegui serializar o banco" ou "o SQL que serializo não abre" — a cópia falha com motivo
	 * em vez de ficar {@code SYNCING} para sempre.
	 *
	 * <p>#953: o nó não tinha rota para contar que a <b>restauração</b> falhou. O estado ficava
	 * {@code SYNCING} eternamente e o painel mostrava uma cópia que nunca ia ficar pronta.</p>
	 */
	@org.springframework.lang.Nullable
	default String lastFailure() {
		return null;
	}

	/**
	 * O dump inteiro do banco <b>conferido</b>: abre o SQL numa conexão real antes de entregar. Um banco
	 * em que o mesmo usuário do app pode ler mas <i>não</i> pode escrever (réplica somente leitura, ou
	 * permissão tirada depois de uma migração) passa pelo {@code SELECT} e quebra no primeiro
	 * {@code CREATE TABLE}; entregá-lo ao nó faria o failover assumir um banco que nem carrega.
	 *
	 * <p>Devolve {@code false} quando a fonte não sabe conferir. O serviço registra a falha e responde
	 * {@code 503} — a cópia não vira {@code READY} por omissão.</p>
	 */
	default boolean verify() {
		return true;
	}
}