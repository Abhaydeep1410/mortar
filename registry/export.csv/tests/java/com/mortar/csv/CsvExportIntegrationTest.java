package com.mortar.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mortar.csv.testapp.Contact;
import com.mortar.csv.testapp.ContactRepository;
import com.mortar.csv.testapp.TestApp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A real JPA entity on an in-memory database, exported through the HTTP endpoint. */
@SpringBootTest(classes = TestApp.class, properties = "mortar.export.csv.batch-size=2")
class CsvExportIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ContactRepository contacts;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    contacts.deleteAll();
  }

  private void save(String name, String email, String notes) {
    Contact contact = new Contact();
    contact.setName(name);
    contact.setEmail(email);
    contact.setNotes(notes);
    contact.setSecret("never-exported");
    contacts.save(contact);
  }

  private String export() throws Exception {
    return mvc.perform(get("/api/contacts/export.csv"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void headerRowListsTheColumnsAndEachContactIsOneRowInIdOrder() throws Exception {
    save("Ada", "ada@example.com", "first");
    save("Grace", "grace@example.com", "second");

    List<String> lines = List.of(export().split("\r\n"));

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0)).isEqualTo("id,name,email,notes");
    assertThat(lines.get(1)).endsWith(",Ada,ada@example.com,first");
    assertThat(lines.get(2)).endsWith(",Grace,grace@example.com,second");
  }

  @Test
  void responseIsAnAttachmentOfTypeTextCsv() throws Exception {
    mvc.perform(get("/api/contacts/export.csv"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"contacts.csv\""));
  }

  @Test
  void anEmptyTableStillReturnsTheHeaderRow() throws Exception {
    assertThat(export()).isEqualTo("id,name,email,notes\r\n");
  }

  @Test
  void rowsSpanningSeveralBatchesAreAllExportedOnceInOrder() throws Exception {
    for (int i = 1; i <= 7; i++) {
      save("Person " + i, "p" + i + "@example.com", "n" + i);
    }

    List<String> lines = List.of(export().split("\r\n"));

    assertThat(lines).hasSize(8);
    for (int i = 1; i <= 7; i++) {
      assertThat(lines.get(i)).contains("Person " + i + ",");
    }
  }

  @Test
  void columnsNotListedAreNeverExported() throws Exception {
    save("Ada", "ada@example.com", "x");

    assertThat(export()).doesNotContain("never-exported").doesNotContain("secret");
  }

  @Test
  void specialCharactersAreQuotedAndFormulasNeutralized() throws Exception {
    save("Smith, Jane", "=HYPERLINK(\"http://evil\")", "line1\nline2 and \"quotes\"");
    save("Zoë Müller", null, "日本語");

    String csv = export();

    assertThat(csv).contains("\"Smith, Jane\"");
    assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    assertThat(csv).contains("\"line1\nline2 and \"\"quotes\"\"\"");
    assertThat(csv).contains(",Zoë Müller,,日本語\r\n");
  }

  @Test
  void unknownColumnsFailAtStartupNotMidDownload() {
    assertThatThrownBy(
            () ->
                new CsvExportController<Contact, ContactRepository>(
                    null, new CsvExportProperties(500, true), List.of("id", "nope"), "x.csv") {})
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
    assertThatThrownBy(
            () ->
                new CsvExportController<Contact, ContactRepository>(
                    null, new CsvExportProperties(500, true), List.of("id"), "../x.csv") {})
        .isInstanceOf(IllegalArgumentException.class);
  }
}
