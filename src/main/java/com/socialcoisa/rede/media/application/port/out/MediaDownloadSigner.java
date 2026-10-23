package com.socialcoisa.rede.media.application.port.out;

/**
 * Assina a leitura do objeto no storage, para provedores que nao aceitam GET sem credencial.
 *
 * Existe para o S3: o gateway S3-compativel proprio (object-storage b7cloud) exige SigV4 em
 * TODA requisicao, GET incluso — nao existe conceito de bucket/objeto publico nele, de proposito
 * (produto multi-tenant privado por padrao). Por isso o `cdn_url` gravado no asset NAO pode ser
 * servido direto ao browser quando o provedor e S3: cada leitura precisa de uma URL assinada nova,
 * gerada aqui. O ImageKit nao usa isto — o CDN dele e publico por natureza.
 */
public interface MediaDownloadSigner {

	/** URL assinada, valida por um tempo, para o browser buscar o objeto direto no bucket. */
	String sign(String objectKey);
}
