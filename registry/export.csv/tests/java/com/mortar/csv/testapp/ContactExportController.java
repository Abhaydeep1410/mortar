package com.mortar.csv.testapp;

import com.mortar.csv.CsvExportController;
import com.mortar.csv.CsvExportProperties;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The secret property is deliberately not exported. */
@RestController
@RequestMapping("/api/contacts")
class ContactExportController extends CsvExportController<Contact, ContactRepository> {

  ContactExportController(ContactRepository repository, CsvExportProperties properties) {
    super(repository, properties, List.of("id", "name", "email", "notes"), "contacts.csv");
  }
}
