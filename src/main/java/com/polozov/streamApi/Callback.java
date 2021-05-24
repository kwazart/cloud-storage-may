package com.polozov.streamApi;

@FunctionalInterface
public interface Callback {
	void call(String value);
}
