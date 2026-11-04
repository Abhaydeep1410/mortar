package com.mortar.audit.testapp;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
class Endpoints {

  private final AtomicLong ids = new AtomicLong(100);

  @GetMapping("/api/contacts")
  String list() {
    return "list";
  }

  @PostMapping("/api/contacts")
  ResponseEntity<String> create(@RequestParam(name = "fail", required = false) String fail) {
    if (fail != null) {
      return ResponseEntity.badRequest().body("invalid");
    }
    long id = ids.incrementAndGet();
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
    return ResponseEntity.created(location).body("created");
  }

  @PutMapping("/api/contacts/{id}")
  ResponseEntity<String> update(@PathVariable("id") long id) {
    return id == 404 ? ResponseEntity.notFound().build() : ResponseEntity.ok("updated");
  }

  @DeleteMapping("/api/contacts/{id}")
  ResponseEntity<Void> delete(@PathVariable("id") long id) {
    return id == 404 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
  }

  @PostMapping("/api/deals")
  ResponseEntity<String> createDeal() {
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(7).toUri();
    return ResponseEntity.created(location).body("deal");
  }

  @PostMapping("/api/unaudited")
  String unaudited() {
    return "not audited";
  }
}
