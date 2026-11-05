package com.mortar.csv;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** Writes RFC 4180 CSV: comma separated, CRLF line ends, double quotes doubled inside quoted cells. */
public final class CsvWriter {

  private final Writer out;
  private final boolean neutralizeFormulas;

  public CsvWriter(Writer out, boolean neutralizeFormulas) {
    this.out = out;
    this.neutralizeFormulas = neutralizeFormulas;
  }

  public void row(List<?> values) throws IOException {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.write(',');
      }
      out.write(cell(values.get(i), neutralizeFormulas));
    }
    out.write("\r\n");
  }

  /** One cell, quoted only when it has to be. {@code null} becomes an empty cell. */
  static String cell(Object value, boolean neutralizeFormulas) {
    if (value == null) {
      return "";
    }
    String text = value.toString();
    if (neutralizeFormulas && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    boolean needsQuotes =
        text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    return needsQuotes ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
  }
}
