package com.mortar.crud;

import java.util.List;
import org.springframework.data.domain.Page;

/** The list response shape of the application contract: {@code {content, totalElements, page, size}}. */
public record PageResponse<T>(List<T> content, long totalElements, int page, int size) {

  public static <T> PageResponse<T> of(Page<T> page) {
    return new PageResponse<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
  }
}
