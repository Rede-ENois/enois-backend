package com.socialcoisa.rede.network.domain;

/**
 * Uma faixa de bytes pedida em {@code Range} (brief §13, "Range Requests"), já validada contra o
 * tamanho do objeto.
 *
 * <p>Só existe a forma de um intervalo — a RFC 9110 também permite {@code bytes=0-1,5-6}, e servir
 * multipart/byteranges para um cliente S3 ou um player é raro o bastante para não justificar o
 * risco de montar o corpo multipart à mão. Range com mais de um intervalo é <b>recusado</b> em vez
 * de servido errado: o cliente cai no corpo inteiro e continua correto.</p>
 *
 * <p>{@code total} vem junto porque o {@code Content-Range} precisa dele: sem o total, a resposta
 * diz onde começa a faixa mas não de um quê, e um player não consegue retomar.</p>
 */
public record ByteRange(long start, long end, long total) {

	/** Os bytes que a faixa cobre, contando as pontas. */
	public long length() {
		return end - start + 1;
	}

	/** O {@code Content-Range} desta resposta. */
	public String contentRange() {
		return "bytes " + start + "-" + end + "/" + total;
	}

	/**
	 * Interpreta um {@code Range} contra o tamanho conhecido.
	 *
	 * <p>Formas aceitas: {@code bytes=a-b}, {@code bytes=a-} e {@code bytes=-n} (os n últimos). É o
	 * que navegador e player mandam de verdade.</p>
	 *
	 * @return a faixa, ou vazio quando o cabeçalho não se aplica — ausente, em outra unidade,
	 *         malformado, com vários intervalos ou começando além do fim. Vazio significa "sirva o
	 *         corpo inteiro", que é uma resposta sempre correta.
	 * @throws Unsatisifiable quando o cabeçalho é uma faixa legítima que não existe no objeto; é o
	 *          caso que a RFC manda responder com 416 e onde o cliente precisa saber que errou.
	 */
	public static java.util.Optional<ByteRange> parse(String header, long total) {
		if (header == null || total <= 0) {
			return java.util.Optional.empty();
		}
		String value = header.trim();
		if (!value.startsWith("bytes=")) {
			return java.util.Optional.empty();
		}
		String spec = value.substring("bytes=".length()).trim();
		if (spec.contains(",")) {
			// Múltiplos intervalos: o corpo inteiro é a resposta correta para quem pediu demais e
			// a única que não arrisca entregar multipart malformado.
			return java.util.Optional.empty();
		}
		int dash = spec.indexOf('-');
		if (dash < 0) {
			return java.util.Optional.empty();
		}
		String from = spec.substring(0, dash).trim();
		String to = spec.substring(dash + 1).trim();
		try {
			if (from.isEmpty()) {
				// `bytes=-n`: os n últimos bytes.
				long suffix = Long.parseLong(to);
				if (suffix <= 0) {
					throw new Unsatisifiable();
				}
				long start = Math.max(0, total - suffix);
				return java.util.Optional.of(new ByteRange(start, total - 1, total));
			}
			long start = Long.parseLong(from);
			if (start >= total) {
				// "starting beyond the end" é 416 pela RFC: o cliente pediu algo que não há.
				throw new Unsatisifiable();
			}
			long end = to.isEmpty() ? total - 1 : Long.parseLong(to);
			if (end < start) {
				return java.util.Optional.empty();
			}
			// Fim além do objeto é truncado, não recusado: a RFC manda servir até o fim.
			return java.util.Optional.of(new ByteRange(start, Math.min(end, total - 1), total));
		}
		catch (NumberFormatException notANumber) {
			return java.util.Optional.empty();
		}
	}

	/** Faixa legítima que não existe no objeto: a resposta é 416 com {@code Content-Range: bytes *}{@code /total}. */
	public static class Unsatisifiable extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public Unsatisifiable() {
			super("faixa fora do objeto");
		}
	}
}