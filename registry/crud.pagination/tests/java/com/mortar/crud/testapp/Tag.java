package com.mortar.crud.testapp;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Served read-only. */
@Entity
public class Tag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String label;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
}
