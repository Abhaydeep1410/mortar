package com.mortar.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ListQueryTest {

  private static final CrudProperties PROPS = new CrudProperties(20, 100);
  private static final Set<String> SORTABLE = Set.of("id", "name", "email");

  private static MultiValueMap<String, String> params(String... keyValues) {
    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.add(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private static ListQuery parse(String... keyValues) {
    return ListQuery.parse(params(keyValues), PROPS, SORTABLE);
  }

  @Test
  void defaultsAreFirstPageDefaultSizeAndStableIdOrder() {
    ListQuery query = parse();

    assertThat(query.pageable().getPageNumber()).isZero();
    assertThat(query.pageable().getPageSize()).isEqualTo(20);
    assertThat(query.pageable().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    assertThat(query.q()).isNull();
  }

  @Test
  void pageSizeAndSortAreParsed() {
    ListQuery query = parse("page", "2", "size", "5", "sort", "name,desc");

    assertThat(query.pageable().getPageNumber()).isEqualTo(2);
    assertThat(query.pageable().getPageSize()).isEqualTo(5);
    assertThat(query.pageable().getSort()).isEqualTo(Sort.by(Sort.Order.desc("name")));
  }

  @Test
  void sortWithoutDirectionIsAscendingAndSeveralSortsKeepTheirOrder() {
    ListQuery query = parse("sort", "name", "sort", "email,desc");

    assertThat(query.pageable().getSort())
        .isEqualTo(Sort.by(Sort.Order.asc("name"), Sort.Order.desc("email")));
  }

  @Test
  void directionIsCaseInsensitiveAndWhitespaceIsTolerated() {
    assertThat(parse("sort", " name , DESC ").pageable().getSort()).isEqualTo(Sort.by(Sort.Order.desc("name")));
  }

  @Test
  void blankQueryTextIsTreatedAsAbsentAndOthersAreStripped() {
    assertThat(parse("q", "   ").q()).isNull();
    assertThat(parse("q", "  Acme ").q()).isEqualTo("Acme");
  }

  @Test
  void sizeAtTheMaximumIsAccepted() {
    assertThat(parse("size", "100").pageable().getPageSize()).isEqualTo(100);
  }

  @Test
  void badInputIsRejectedWithAClearMessage() {
    assertThatThrownBy(() -> parse("page", "-1")).isInstanceOf(InvalidListQueryException.class).hasMessageContaining("page");
    assertThatThrownBy(() -> parse("page", "abc")).hasMessageContaining("whole number");
    assertThatThrownBy(() -> parse("size", "0")).hasMessageContaining("size");
    assertThatThrownBy(() -> parse("size", "101")).hasMessageContaining("between 1 and 100");
    assertThatThrownBy(() -> parse("size", "1.5")).hasMessageContaining("whole number");
    assertThatThrownBy(() -> parse("sort", "password,asc")).hasMessageContaining("cannot sort by 'password'");
    assertThatThrownBy(() -> parse("sort", "name,sideways")).hasMessageContaining("asc or desc");
    assertThatThrownBy(() -> parse("sort", "name,asc,extra")).hasMessageContaining("sort must look like");
  }

  @Test
  void propertiesRejectNonsense() {
    assertThatThrownBy(() -> new CrudProperties(0, 100)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CrudProperties(50, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CrudProperties(20, 5000)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void settingsAlwaysAllowSortingById() {
    assertThat(CrudSettings.of(Set.of("name"), Set.of()).sortableFields()).containsExactlyInAnyOrder("id", "name");
  }
}
