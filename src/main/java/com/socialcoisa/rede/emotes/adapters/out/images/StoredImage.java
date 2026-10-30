package com.socialcoisa.rede.emotes.adapters.out.images;

/**
 * Imagem de figurinha baixada do CDN do provedor, pronta para ser servida.
 *
 * O `contentType` vem do CDN e e validado antes (`image/*`); o `bytes` nunca passa de
 * {@link EmoteImageCache#MAX_BYTES} — figurinha de chat e arquivo pequeno, e um CDN que devolva
 * outra coisa nao pode encher a memoria do pod.
 */
public record StoredImage(byte[] bytes, String contentType) {
}