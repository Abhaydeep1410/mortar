package com.reklo.crud.testapp;

import com.reklo.crud.CrudController;
import com.reklo.crud.CrudProperties;
import com.reklo.crud.CrudSettings;
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
