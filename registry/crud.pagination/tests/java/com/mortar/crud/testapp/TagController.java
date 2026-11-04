package com.mortar.crud.testapp;

import com.mortar.crud.CrudController;
import com.mortar.crud.CrudProperties;
import com.mortar.crud.CrudSettings;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tags")
class TagController extends CrudController<Tag, TagRepository> {

  TagController(TagRepository repository, CrudProperties properties) {
    super(repository, properties, CrudSettings.of(Set.of("label"), Set.of("label")).asReadOnly());
  }
}
