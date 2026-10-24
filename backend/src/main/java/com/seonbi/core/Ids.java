package com.seonbi.core;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

public final class Ids {
  private Ids() {}

  public static UUID next() {
    return UuidCreator.getTimeOrderedEpoch();
  }
}
