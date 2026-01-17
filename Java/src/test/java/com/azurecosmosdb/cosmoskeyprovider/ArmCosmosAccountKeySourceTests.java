package com.azurecosmosdb.cosmoskeyprovider;

import com.azure.resourcemanager.cosmos.fluent.models.DatabaseAccountListKeysResultInner;
import com.azure.resourcemanager.cosmos.models.DatabaseAccountListKeysResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ArmCosmosAccountKeySourceTests {
  @Test
  void toCosmosAccountKeys_mapsPrimaryAndSecondary() {
    DatabaseAccountListKeysResult result = new DatabaseAccountListKeysResult() {
      @Override
      public String primaryMasterKey() {
        return "primary";
      }

      @Override
      public String secondaryMasterKey() {
        return "secondary";
      }

      @Override
      public String primaryReadonlyMasterKey() {
        return "ro-primary";
      }

      @Override
      public String secondaryReadonlyMasterKey() {
        return "ro-secondary";
      }

      @Override
      public DatabaseAccountListKeysResultInner innerModel() {
        return null;
      }
    };

    CosmosAccountKeys keys = ArmCosmosAccountKeySource.toCosmosAccountKeys(result);
    assertEquals("primary", keys.primaryMasterKey());
    assertEquals("secondary", keys.secondaryMasterKey());
  }

  @Test
  void toCosmosAccountKeys_rejectsBlankKeys() {
    DatabaseAccountListKeysResult result = new DatabaseAccountListKeysResult() {
      @Override
      public String primaryMasterKey() {
        return "";
      }

      @Override
      public String secondaryMasterKey() {
        return "secondary";
      }

      @Override
      public String primaryReadonlyMasterKey() {
        return null;
      }

      @Override
      public String secondaryReadonlyMasterKey() {
        return null;
      }

      @Override
      public DatabaseAccountListKeysResultInner innerModel() {
        return null;
      }
    };

    assertThrows(IllegalArgumentException.class, () -> ArmCosmosAccountKeySource.toCosmosAccountKeys(result));
  }
}
