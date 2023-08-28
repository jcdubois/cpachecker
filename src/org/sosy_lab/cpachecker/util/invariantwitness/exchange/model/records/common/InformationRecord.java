// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.invariantwitness.exchange.model.records.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.errorprone.annotations.Immutable;

@Immutable
public class InformationRecord {
  @JsonProperty("string")
  private final String string;

  @JsonProperty("type")
  private final String type;

  @JsonProperty("format")
  private final String format;

  public InformationRecord(
      @JsonProperty("string") String string,
      @JsonProperty("type") String type,
      @JsonProperty("format") String format) {
    this.string = string;
    this.type = type;
    this.format = format;
  }

  public String getString() {
    return string;
  }

  public String getType() {
    return type;
  }

  public String getFormat() {
    return format;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof InformationRecord invariantStoreEntryLoopInvariant
        && Objects.equal(string, invariantStoreEntryLoopInvariant.string)
        && Objects.equal(type, invariantStoreEntryLoopInvariant.type)
        && Objects.equal(format, invariantStoreEntryLoopInvariant.format);
  }

  @Override
  public int hashCode() {
    int hashCode = string.hashCode();
    hashCode = 31 * hashCode + (type != null ? type.hashCode() : 0);
    hashCode = 31 * hashCode + (format != null ? format.hashCode() : 0);
    return hashCode;
  }

  @Override
  public String toString() {
    return "InformationRecord{"
        + " string='"
        + getString()
        + "'"
        + ", type='"
        + getType()
        + "'"
        + ", format='"
        + getFormat()
        + "'"
        + "}";
  }
}
