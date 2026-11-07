package com.socialcoisa.rede.media.adapters.out.classification;

import java.nio.charset.StandardCharsets;

/**
 * Animation flags read from the container, for formats whose ImageIO reader only exposes the still
 * image while browsers play the animation (APNG, animated WebP, AVIF image sequences). Those frames
 * would never reach the classifier, so such files are refused instead of scored on one frame.
 */
final class AnimatedContainers {

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
	private static final int WEBP_ANIMATION_FLAG = 0x02;

	private AnimatedContainers() {
	}

	static boolean isAnimated(byte[] data) {
		return isAnimatedPng(data) || isAnimatedWebp(data) || isAvifSequence(data);
	}

	/** An {@code acTL} chunk before the first {@code IDAT} marks an APNG. */
	private static boolean isAnimatedPng(byte[] data) {
		if (!startsWith(data, 0, PNG_SIGNATURE)) {
			return false;
		}
		long index = PNG_SIGNATURE.length;
		while (index + 8 <= data.length) {
			long length = bigEndianInt(data, (int) index) & 0xFFFFFFFFL;
			String type = ascii(data, (int) index + 4, 4);
			if ("acTL".equals(type)) {
				return true;
			}
			if ("IDAT".equals(type) || "IEND".equals(type)) {
				return false;
			}
			index += 12 + length;
		}
		return false;
	}

	/** Extended WebP ({@code VP8X}) carries the animation bit in its flags byte. */
	private static boolean isAnimatedWebp(byte[] data) {
		if (data.length < 21 || !"RIFF".equals(ascii(data, 0, 4)) || !"WEBP".equals(ascii(data, 8, 4))) {
			return false;
		}
		return "VP8X".equals(ascii(data, 12, 4)) && (data[20] & WEBP_ANIMATION_FLAG) != 0;
	}

	/** ISO-BMFF {@code ftyp} brands: {@code avis} is the AVIF image-sequence brand. */
	private static boolean isAvifSequence(byte[] data) {
		if (data.length < 16 || !"ftyp".equals(ascii(data, 4, 4))) {
			return false;
		}
		long boxSize = bigEndianInt(data, 0) & 0xFFFFFFFFL;
		int end = (int) Math.min(data.length, Math.max(16, boxSize));
		if ("avis".equals(ascii(data, 8, 4))) {
			return true;
		}
		for (int brand = 16; brand + 4 <= end; brand += 4) {
			if ("avis".equals(ascii(data, brand, 4))) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
		if (data.length < offset + prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (data[offset + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static int bigEndianInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
			| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}

	private static String ascii(byte[] data, int offset, int length) {
		return new String(data, offset, length, StandardCharsets.US_ASCII);
	}
}
