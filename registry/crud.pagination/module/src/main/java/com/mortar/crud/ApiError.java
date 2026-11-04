package com.mortar.crud;

/** Error body of the application contract: a JSON object with a {@code message}. */
public record ApiError(String message) {}
