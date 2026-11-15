package com.socialcoisa.rede.network.domain;

/**
 * Estado do objeto LÓGICO no catálogo do Storage (V319).
 *
 * <p>O estado é do objeto, não de uma cópia: um objeto em {@code STORED} pode ter cópias em vários
 * estados simultaneamente (a fase 3 cuida disso, por colocação). Aqui a pergunta é "existe alguma
 * cópia inteira e conferida deste objeto?", e é isso que decide se o download pode ser servido.</p>
 *
 * <p>{@code DELETED} é lápide, não remoção de linha. A chave continua ocupada: recriar o mesmo
 * {@code bucket + object_key} depois da lápide é um objeto novo, e é por isso que o catálogo tem
 * identidade própria ({@code id}) em vez de a chave ser a chave primária. Sem a lápide, a fase 3
 * não teria como varrer as cópias órfãs nos nós — o catálogo esqueceria que aquele arquivo existiu
 * e o disco do participante ficaria ocupado para sempre.</p>
 */
public enum StorageObjectState {

	/** Upload criado, nenhuma cópia confirmada ainda. Ler o objeto devolve 404. */
	PENDING,

	/** Ao menos uma cópia inteira, medido e conferido pelo Control Plane. É o único estado servível. */
	STORED,

	/** Lápide: a chave foi removida e as cópias aguardam coleta. Não serve download. */
	DELETED;

	/** Só o que tem cópia conferida pode ser entregue — "PENDING" não é "vazio". */
	public boolean downloadable() {
		return this == STORED;
	}
}