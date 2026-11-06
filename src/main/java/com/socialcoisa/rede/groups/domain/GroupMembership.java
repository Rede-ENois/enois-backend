package com.socialcoisa.rede.groups.domain;

/** Um grupo do ponto de vista de quem participa dele. */
public record GroupMembership(Group group, GroupRole role) {
}
