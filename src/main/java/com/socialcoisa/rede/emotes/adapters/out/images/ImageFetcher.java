package com.socialcoisa.rede.emotes.adapters.out.images;

/** Busca os bytes de uma imagem no CDN do provedor. Separado para o teste rodar sem rede. */
@FunctionalInterface
public interface ImageFetcher {

	StoredImage fetch(String url) throws Exception;

}