package com.socialcoisa.rede.studypairs.domain;

/** Aggregate over open requests: no user id, no author, only the goal and how many wait on it. */
public record PopularGoal(String goalKey, String goal, String topic, int openRequests) {
}
