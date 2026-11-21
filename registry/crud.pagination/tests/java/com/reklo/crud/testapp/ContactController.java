package com.reklo.crud.testapp;

import com.reklo.crud.CrudController;
import com.reklo.crud.CrudProperties;
import com.reklo.crud.CrudSettings;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contacts")
class ContactController extends CrudController<Contact, ContactRepository> {

  ContactController(ContactRepository repository, CrudProperties properties) {
    super(
        repository,
        properties,
        CrudSettings.of(Set.of("name", "email", "company"), Set.of("name", "email", "company")));
  }

  @Override
  protected void beforeSave(Contact contact, boolean creating) {
    if (creating) {
      contact.setCreatedBy("server");
    }
  }
}
