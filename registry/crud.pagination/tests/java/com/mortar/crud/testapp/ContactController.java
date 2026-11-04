package com.mortar.crud.testapp;

import com.mortar.crud.CrudController;
import com.mortar.crud.CrudProperties;
import com.mortar.crud.CrudSettings;
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
