package com.reklo.csv.testapp;

import com.reklo.csv.CsvExportController;
import com.reklo.csv.CsvExportProperties;
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
