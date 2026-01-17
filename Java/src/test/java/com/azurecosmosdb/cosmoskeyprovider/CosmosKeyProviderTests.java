package com.azurecosmosdb.cosmoskeyprovider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

public class CosmosKeyProviderTests {
  @Test
  void coldStartSingleFlight_onlyOneControlPlaneCall() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<CosmosAccountKeys> sourceFuture = new CompletableFuture<>();

    CosmosAccountKeySource keySource = () -> {
      calls.incrementAndGet();
      return sourceFuture;
    };

    CosmosKeyProvider provider = new CosmosKeyProvider(
        keySource,
        new CosmosKeyProviderOptions(Duration.ofMinutes(5)),
        TestClock.fixedUtc(Instant.parse("2026-01-01T00:00:00Z")),
        LoggerFactory.getLogger(CosmosKeyProvider.class));

    int concurrency = 50;
    CompletableFuture<?>[] futures = new CompletableFuture<?>[concurrency];
    for (int i = 0; i < concurrency; i++) {
      futures[i] = provider.getPrimaryKey();
    }

    assertEquals(1, calls.get(), "expected a single keySource.getKeys call");

    sourceFuture.complete(new CosmosAccountKeys("primary", "secondary"));

    CompletableFuture.allOf(futures).join();
    assertEquals("primary", provider.getPrimaryKey().join());
    assertEquals("secondary", provider.getSecondaryKey().join());
  }

  @Test
  void minRefreshInterval_throttlesNonForcedRefresh() {
    AtomicInteger calls = new AtomicInteger();
    Queue<CosmosAccountKeys> returns = new ArrayDeque<>();
    returns.add(new CosmosAccountKeys("p1", "s1"));
    returns.add(new CosmosAccountKeys("p2", "s2"));

    CosmosAccountKeySource keySource = () -> {
      calls.incrementAndGet();
      return CompletableFuture.completedFuture(returns.remove());
    };

    TestClock clock = TestClock.fixedUtc(Instant.parse("2026-01-01T00:00:00Z"));

    CosmosKeyProvider provider = new CosmosKeyProvider(
        keySource,
        new CosmosKeyProviderOptions(Duration.ofSeconds(10)),
        clock,
        LoggerFactory.getLogger(CosmosKeyProvider.class));

    assertEquals("p1", provider.getPrimaryKey().join());
    assertEquals(1, calls.get());

    // Within the interval: should not call key source again.
    assertEquals("p1", provider.getPrimaryKey().join());
    provider.refreshKeys(false).join();
    assertEquals(1, calls.get());

    // Outside the interval: should refresh.
    clock.advanceSeconds(11);
    provider.refreshKeys(false).join();
    assertEquals(2, calls.get());
    assertEquals("p2", provider.getPrimaryKey().join());
  }
}
