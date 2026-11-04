package com.mortar.crud;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST CRUD for one JPA entity with a numeric {@code Long id}. Subclass it, add
 * {@code @RestController} and {@code @RequestMapping("/api/<plural>")}, and pass the repository:
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/contacts")
 * class ContactController extends CrudController<Contact, ContactRepository> {
 *   ContactController(ContactRepository repo, CrudProperties props) {
 *     super(repo, props, CrudSettings.of(Set.of("name", "email"), Set.of("name", "email", "company")));
 *   }
 * }
 * }</pre>
 *
 * <p>Endpoints: {@code GET} (paged list: {@code page}, {@code size}, {@code sort=field,asc|desc},
 * {@code q}), {@code GET /{id}}, {@code POST} (201 with a {@code Location} header), {@code PUT /{id}} (200), {@code DELETE /{id}}
 * (204). Unknown id is 404; bad input is 400 with a {@code message}. Override {@link #beforeSave}
 * for app rules such as stamping the author.
 *
 * @param <T> the entity type
 * @param <R> its repository, which must also support specifications
 */
public abstract class CrudController<T, R extends JpaRepository<T, Long> & JpaSpecificationExecutor<T>> {

  private final R repository;
  private final CrudProperties properties;
  private final CrudSettings settings;

  protected CrudController(R repository, CrudProperties properties, CrudSettings settings) {
    this.repository = repository;
    this.properties = properties;
    this.settings = settings;
  }

  @GetMapping
  public PageResponse<T> list(@RequestParam MultiValueMap<String, String> params) {
    ListQuery query = ListQuery.parse(params, properties, settings.sortableFields());
    if (query.q() == null || settings.searchFields().isEmpty()) {
      return PageResponse.of(repository.findAll(query.pageable()));
    }
    return PageResponse.of(repository.findAll(containsIgnoreCase(query.q()), query.pageable()));
  }

  @GetMapping("/{id}")
  public T get(@PathVariable("id") Long id) {
    return repository.findById(id).orElseThrow(() -> notFound(id));
  }

  @PostMapping
  public ResponseEntity<T> create(@Valid @RequestBody T body) {
    requireWritable();
    setId(body, null);
    beforeSave(body, true);
    T saved = repository.save(body);
    Object id = PropertyAccessorFactory.forBeanPropertyAccess(saved).getPropertyValue("id");
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
    return ResponseEntity.created(location).body(saved);
  }

  @PutMapping("/{id}")
  public T update(@PathVariable("id") Long id, @Valid @RequestBody T body) {
    requireWritable();
    if (!repository.existsById(id)) {
      throw notFound(id);
    }
    setId(body, id);
    beforeSave(body, false);
    return repository.save(body);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
    requireWritable();
    if (!repository.existsById(id)) {
      throw notFound(id);
    }
    repository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  /** Hook run before every create ({@code creating == true}) and update. Does nothing by default. */
  protected void beforeSave(T entity, boolean creating) {}

  /** Sets the {@code id} property. Override if the entity does not use a standard setter. */
  protected void setId(T entity, Long id) {
    BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(entity);
    wrapper.setPropertyValue("id", id);
  }

  private void requireWritable() {
    if (settings.readOnly()) {
      throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "this resource is read-only");
    }
  }

  private static NotFoundException notFound(Long id) {
    return new NotFoundException("no entity with id " + id);
  }

  private Specification<T> containsIgnoreCase(String text) {
    String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
    List<String> fields = new ArrayList<>(settings.searchFields());
    return (root, query, builder) ->
        builder.or(
            fields.stream()
                .map(field -> builder.like(builder.lower(root.get(field).as(String.class)), pattern, '\\'))
                .toArray(Predicate[]::new));
  }

  private static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
