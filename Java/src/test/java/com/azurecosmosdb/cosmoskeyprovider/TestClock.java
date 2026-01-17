package com.azurecosmosdb.cosmoskeyprovider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

final class TestClock extends Clock {
  private Instant instant;
  private final ZoneId zone;

  TestClock(Instant instant, ZoneId zone) {
    this.instant = Objects.requireNonNull(instant, "instant");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  static TestClock fixedUtc(Instant instant) {
    return new TestClock(instant, ZoneId.of("UTC"));
  }

  void setInstant(Instant instant) {
    this.instant = Objects.requireNonNull(instant, "instant");
  }

  void advanceSeconds(long seconds) {
    this.instant = this.instant.plusSeconds(seconds);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new TestClock(instant, zone);
  }

  @Override
  public Instant instant() {
    return instant;
  }
}
