package com.mortar.crud;

/** A bad {@code page}, {@code size}, {@code sort} or {@code q} parameter. Answered with 400. */
public class InvalidListQueryException extends RuntimeException {

  public InvalidListQueryException(String message) {
    super(message);
  }
}
