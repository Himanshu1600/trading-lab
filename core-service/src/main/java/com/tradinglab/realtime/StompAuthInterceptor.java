package com.tradinglab.realtime;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Browsers can't set headers on a WebSocket handshake, so the access token travels in the STOMP
 * CONNECT frame instead: {@code Authorization: Bearer <token>}. The handshake itself is public.
 * Subscriptions are limited to quote topics and the user's own queues.
 */
@Component
class StompAuthInterceptor implements ChannelInterceptor {

	private final JwtDecoder jwtDecoder;

	StompAuthInterceptor(JwtDecoder jwtDecoder) {
		this.jwtDecoder = jwtDecoder;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}
		if (accessor.getCommand() == StompCommand.CONNECT) {
			accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
		}
		else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
			String destination = accessor.getDestination();
			boolean allowed = destination != null
					&& (destination.startsWith(QuoteBroadcaster.TOPIC_PREFIX) || destination.startsWith("/user/queue/"));
			if (accessor.getUser() == null || !allowed) {
				throw new MessageDeliveryException("Subscription not allowed: " + destination);
			}
		}
		else if (accessor.getCommand() == StompCommand.SEND) {
			throw new MessageDeliveryException("This endpoint is receive-only");
		}
		return message;
	}

	private Principal authenticate(String header) {
		if (header == null || !header.startsWith("Bearer ")) {
			throw new MessageDeliveryException("Missing access token");
		}
		try {
			String userId = jwtDecoder.decode(header.substring("Bearer ".length())).getSubject();
			return new StompUser(userId);
		}
		catch (JwtException ex) {
			throw new MessageDeliveryException("Invalid access token");
		}
	}

	/** The STOMP session's user. Its name is the user ID, which is what {@code convertAndSendToUser} targets. */
	record StompUser(String userId) implements Principal {

		@Override
		public String getName() {
			return userId;
		}

	}

}
