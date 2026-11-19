package com.socialcoisa.rede.network.application;

/**
 * De onde sai o SQL do banco do Control Plane para a cópia no nó de reserva (V360).
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
}