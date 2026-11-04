package com.mortar.crud;

/** No entity with the requested id. Answered with 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
