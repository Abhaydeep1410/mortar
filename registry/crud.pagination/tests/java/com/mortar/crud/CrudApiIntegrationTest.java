package com.mortar.crud;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mortar.crud.testapp.Contact;
import com.mortar.crud.testapp.ContactRepository;
import com.mortar.crud.testapp.Tag;
import com.mortar.crud.testapp.TagRepository;
import com.mortar.crud.testapp.TestApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A real JPA entity on an in-memory database, exercised through the HTTP contract. */
@SpringBootTest(classes = TestApp.class)
class CrudApiIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ContactRepository contacts;
  @Autowired private TagRepository tags;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    contacts.deleteAll();
    tags.deleteAll();
  }

  private void seed(int count) {
    for (int i = 1; i <= count; i++) {
      Contact contact = new Contact();
      contact.setName(String.format("Person %02d", i));
      contact.setEmail("person" + i + "@example.com");
      contact.setCompany(i % 5 == 0 ? "Acme_100%" : "Globex");
      contacts.save(contact);
    }
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private long createViaApi(String body) throws Exception {
    String response =
        mvc.perform(json(post("/api/contacts"), body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(response, "$.id")).longValue();
  }

  @Test
  void listIsPagedAndReportsTotals() throws Exception {
    seed(25);

    mvc.perform(get("/api/contacts?page=0&size=10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(10))
        .andExpect(jsonPath("$.totalElements").value(25))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.content[0].name").value("Person 01"));
    mvc.perform(get("/api/contacts?page=2&size=10"))
        .andExpect(jsonPath("$.content.length()").value(5))
        .andExpect(jsonPath("$.page").value(2));
    mvc.perform(get("/api/contacts?page=3&size=10"))
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(25));
  }

  @Test
  void listWithoutParametersUsesTheDefaultSize() throws Exception {
    seed(25);

    mvc.perform(get("/api/contacts"))
        .andExpect(jsonPath("$.content.length()").value(20))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalElements").value(25));
  }

  @Test
  void sortHonoursFieldDirectionAndSecondarySorts() throws Exception {
    seed(5);

    mvc.perform(get("/api/contacts?sort=name,desc&size=2"))
        .andExpect(jsonPath("$.content[0].name").value("Person 05"))
        .andExpect(jsonPath("$.content[1].name").value("Person 04"));
    mvc.perform(get("/api/contacts?sort=company,asc&sort=name,desc"))
        .andExpect(jsonPath("$.content[0].company").value("Acme_100%"))
        .andExpect(jsonPath("$.content[1].company").value("Globex"))
        .andExpect(jsonPath("$.content[1].name").value("Person 04"));
  }

  @Test
  void badListParametersAreBadRequestsWithAMessage() throws Exception {
    for (String query : new String[] {"page=-1", "size=0", "size=1000", "page=x", "sort=password,asc", "sort=name,up"}) {
      mvc.perform(get("/api/contacts?" + query))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").isNotEmpty());
    }
  }

  @Test
  void searchMatchesAnySearchFieldIgnoringCaseAndStillPages() throws Exception {
    seed(25);

    mvc.perform(get("/api/contacts").param("q", "PERSON 07"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].email").value("person7@example.com"));
    mvc.perform(get("/api/contacts").param("q", "globex").param("size", "5"))
        .andExpect(jsonPath("$.totalElements").value(20))
        .andExpect(jsonPath("$.content.length()").value(5));
    mvc.perform(get("/api/contacts").param("q", "@EXAMPLE.com")).andExpect(jsonPath("$.totalElements").value(25));
    mvc.perform(get("/api/contacts").param("q", "nothing-matches")).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void searchTreatsLikeWildcardsAsPlainText() throws Exception {
    seed(25);

    mvc.perform(get("/api/contacts").param("q", "%")).andExpect(jsonPath("$.totalElements").value(5));
    mvc.perform(get("/api/contacts").param("q", "_")).andExpect(jsonPath("$.totalElements").value(5));
    mvc.perform(get("/api/contacts").param("q", "100%")).andExpect(jsonPath("$.totalElements").value(5));
  }

  @Test
  void createReturns201AssignsAnIdAndIgnoresAClientSuppliedId() throws Exception {
    String body = "{\"id\":999,\"name\":\"Ada\",\"email\":\"ada@example.com\",\"company\":\"Initech\"}";

    ResultActions result =
        mvc.perform(json(post("/api/contacts"), body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Ada"))
            .andExpect(jsonPath("$.createdBy").value("server"));
    long id = ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();

    org.assertj.core.api.Assertions.assertThat(id).isNotEqualTo(999L);
    mvc.perform(get("/api/contacts/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.company").value("Initech"));
  }

  @Test
  void invalidAndMalformedBodiesAreBadRequestsWithAMessage() throws Exception {
    mvc.perform(json(post("/api/contacts"), "{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    mvc.perform(json(post("/api/contacts"), "not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").isNotEmpty());
    mvc.perform(post("/api/contacts").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void updateReplacesTheEntityAndTakesTheIdFromThePath() throws Exception {
    long id = createViaApi("{\"name\":\"Before\",\"company\":\"Old\"}");

    mvc.perform(json(put("/api/contacts/" + id), "{\"id\":12345,\"name\":\"After\",\"company\":\"New\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("After"));
    mvc.perform(get("/api/contacts/" + id)).andExpect(jsonPath("$.company").value("New"));
    mvc.perform(get("/api/contacts?size=100")).andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void unknownIdsAre404ForGetPutAndDeleteAndInvalidUpdatesAre400() throws Exception {
    mvc.perform(get("/api/contacts/424242"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").isNotEmpty());
    mvc.perform(json(put("/api/contacts/424242"), "{\"name\":\"x\"}")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/contacts/424242")).andExpect(status().isNotFound());

    long id = createViaApi("{\"name\":\"Keep\"}");
    mvc.perform(json(put("/api/contacts/" + id), "{\"name\":\"\"}")).andExpect(status().isBadRequest());
  }

  @Test
  void deleteReturns204ThenTheEntityIsGone() throws Exception {
    long id = createViaApi("{\"name\":\"Temp\"}");

    mvc.perform(delete("/api/contacts/" + id)).andExpect(status().isNoContent());
    mvc.perform(get("/api/contacts/" + id)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/contacts/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void readOnlyResourcesListAndFetchButRefuseWrites() throws Exception {
    Tag tag = new Tag();
    tag.setLabel("vip");
    long id = tags.save(tag).getId();

    mvc.perform(get("/api/tags")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/tags/" + id)).andExpect(status().isOk());
    mvc.perform(json(post("/api/tags"), "{\"label\":\"x\"}")).andExpect(status().isMethodNotAllowed());
    mvc.perform(json(put("/api/tags/" + id), "{\"label\":\"x\"}")).andExpect(status().isMethodNotAllowed());
    mvc.perform(delete("/api/tags/" + id)).andExpect(status().isMethodNotAllowed());
  }
}
