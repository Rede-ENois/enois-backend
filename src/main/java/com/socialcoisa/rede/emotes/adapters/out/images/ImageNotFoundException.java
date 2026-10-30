package com.socialcoisa.rede.emotes.adapters.out.images;

/**
 * O CDN respondeu que a imagem NAO EXISTE (404/410) — caso esperado para figurinha sem versao
 * animada, em que o proxy cai para o arquivo estatico.
 *
 * Separado de falha transitoria de proposito: timeout merece nova tentativa, "nao existe" nao.
 */
public class ImageNotFoundException extends Exception {

	public ImageNotFoundException(String message) {
		super(message);
	}
}