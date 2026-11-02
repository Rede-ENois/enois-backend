package com.socialcoisa.rede.notifications.domain;

/**
 * The community or group a notification came from. {@code color} is null while communities and
 * groups have no color of their own.
 */
public record NotificationPlace(Type type, String id, String name, String color) {

	public enum Type {
		COMMUNITY, GROUP
	}
}
