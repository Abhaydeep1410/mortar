package com.mortar.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

  private static String row(boolean neutralize, Object... values) throws IOException {
    StringWriter out = new StringWriter();
    new CsvWriter(out, neutralize).row(Arrays.asList(values));
    return out.toString();
  }

  @Test
  void plainValuesAreUnquotedAndRowsEndWithCrLf() throws IOException {
    assertThat(row(true, 1, "Ada", true)).isEqualTo("1,Ada,true\r\n");
  }

  @Test
  void nullBecomesAnEmptyCell() throws IOException {
    assertThat(row(true, "a", null, "c")).isEqualTo("a,,c\r\n");
  }

  @Test
  void commasQuotesAndLineBreaksForceQuotingAndQuotesAreDoubled() throws IOException {
    assertThat(row(true, "Smith, Jane")).isEqualTo("\"Smith, Jane\"\r\n");
    assertThat(row(true, "say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"\r\n");
    assertThat(row(true, "two\nlines")).isEqualTo("\"two\nlines\"\r\n");
    assertThat(row(true, "cr\rhere")).isEqualTo("\"cr\rhere\"\r\n");
  }

  @Test
  void formulaLookingCellsAreNeutralizedWhenEnabled() throws IOException {
    for (String risky : List.of("=1+1", "+44 20 7946 0000", "-5", "@SUM(A1)", "\tx")) {
      assertThat(CsvWriter.cell(risky, true)).startsWith("'" + risky.charAt(0));
    }
    assertThat(CsvWriter.cell("=1+1", false)).isEqualTo("=1+1");
    assertThat(CsvWriter.cell("a=b", true)).isEqualTo("a=b");
    assertThat(CsvWriter.cell("", true)).isEmpty();
  }

  @Test
  void aNeutralizedCellThatAlsoNeedsQuotingIsQuotedOnce() {
    assertThat(CsvWriter.cell("=A1,B1", true)).isEqualTo("\"'=A1,B1\"");
  }

  @Test
  void propertiesRejectBadBatchSizes() {
    assertThatThrownBy(() -> new CsvExportProperties(0, true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CsvExportProperties(5001, true)).isInstanceOf(IllegalArgumentException.class);
  }
}
