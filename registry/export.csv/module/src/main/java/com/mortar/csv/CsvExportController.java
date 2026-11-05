package com.mortar.csv;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * {@code GET <base>/export.csv} for one JPA entity: a header row of the column names, then one row
 * per entity ordered by {@code id}, read in batches so a large table is never held in memory.
 * Subclass it with the same {@code @RequestMapping} as the entity's other controller:
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/contacts")
 * class ContactExportController extends CsvExportController<Contact, ContactRepository> {
 *   ContactExportController(ContactRepository repo, CsvExportProperties props) {
 *     super(repo, props, List.of("id", "name", "email", "company"), "contacts.csv");
 *   }
 * }
 * }</pre>
 *
 * <p>Columns are listed explicitly so a sensitive property can never be exported by accident. This
 * class does not check permissions; {@code authorization.rbac} guards the path with the
 * {@code export} permission.
 *
 * @param <T> the entity type, which needs an {@code id} property and getters for every column
 * @param <R> its repository
 */
public abstract class CsvExportController<T, R extends JpaRepository<T, Long>> {

  private final R repository;
  private final CsvExportProperties properties;
  private final List<String> columns;
  private final String fileName;

  protected CsvExportController(
      R repository, CsvExportProperties properties, List<String> columns, String fileName) {
    if (columns == null || columns.isEmpty()) {
      throw new IllegalArgumentException("at least one column is required");
    }
    if (!fileName.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("fileName may only contain letters, digits, '.', '_' and '-': " + fileName);
    }
    Class<?> entityType = ResolvableType.forClass(CsvExportController.class, getClass()).getGeneric(0).resolve();
    if (entityType != null) {
      for (String column : columns) {
        var descriptor = BeanUtils.getPropertyDescriptor(entityType, column);
        if (descriptor == null || descriptor.getReadMethod() == null) {
          throw new IllegalArgumentException(entityType.getSimpleName() + " has no readable property '" + column + "'");
        }
      }
    }
    this.repository = repository;
    this.properties = properties;
    this.columns = List.copyOf(columns);
    this.fileName = fileName;
  }

  @GetMapping("/export.csv")
  public void export(HttpServletResponse response) throws IOException {
    response.setContentType("text/csv");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

    PrintWriter out = response.getWriter();
    CsvWriter csv = new CsvWriter(out, properties.neutralizeFormulas());
    csv.row(columns);

    PageRequest request = PageRequest.of(0, properties.batchSize(), Sort.by(Sort.Direction.ASC, "id"));
    Page<T> page;
    do {
      page = repository.findAll(request);
      for (T entity : page.getContent()) {
        csv.row(values(entity));
      }
      request = request.next();
    } while (page.hasNext());
    out.flush();
  }

  private List<Object> values(T entity) {
    BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(entity);
    List<Object> values = new ArrayList<>(columns.size());
    for (String column : columns) {
      values.add(wrapper.getPropertyValue(column));
    }
    return values;
  }
}
