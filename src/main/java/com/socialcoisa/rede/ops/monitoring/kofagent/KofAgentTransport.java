package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;

/** Delivers one message to the channel. Called only from the dispatcher thread. */
interface KofAgentTransport {

	SendResult send(String text, boolean silent, boolean html);

	/** Checks the bot identity and that it can reach the configured chat; returns a log-safe summary. */
	SendResult verify();

	record SendResult(Outcome outcome, Duration retryAfter, String detail) {

		static SendResult sent() {
			return new SendResult(Outcome.SENT, null, null);
		}

		static SendResult of(Outcome outcome, String detail) {
			return new SendResult(outcome, null, detail);
		}
	}

	enum Outcome {
		/** Delivered. */
		SENT,
		/** Network failure, timeout or Telegram 5xx: try again with backoff. */
		RETRY,
		/** Telegram 429: wait {@code retryAfter} and try again; not a failure of the channel. */
		RATE_LIMITED,
		/** Wrong token, chat not found, bot not admin: retry with backoff, fixing the config recovers it. */
		CONFIG_ERROR,
		/** The markup was rejected: resend as plain text. */
		BAD_MARKUP,
		/** Telegram refuses this content for good (e.g. too long): drop it. */
		REJECTED
	}
}
