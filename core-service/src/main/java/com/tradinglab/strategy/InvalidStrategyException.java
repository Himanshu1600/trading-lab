package com.tradinglab.strategy;

/** The strategy JSON breaks a rule. The message names the field, such as {@code entry.all[1].period}. */
public class InvalidStrategyException extends RuntimeException {

	private final String path;

	InvalidStrategyException(String path, String problem) {
		super(path.isEmpty() ? problem : path + ": " + problem);
		this.path = path;
	}

	public String path() {
		return path;
	}

}
