package org.opengroup.osdu.indexerqueue.reference.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties
@Getter
@Setter
public class IndexerQueueConfigProperties {

  private String indexerUrl;
  private Integer schemaCacheExpiration = 60;
  private Integer indexCacheExpiration = 60;
  private Integer elasticCacheExpiration = 1440;
  private Integer cursorCacheExpiration = 60;

  //Kinds Cache expiration 2*24*60
  private Integer kindsCacheExpiration = 2880;

  //Attributes Cache expiration 2*24*60
  private Integer attributesCacheExpiration = 2880;

  private Integer kindsRedisDatabase = 1;

  private String environment;
  private String indexerHost;

  private String googleCloudProject;
  private String googleCloudProjectRegion;
  private String googleAudiences;

  public String getDeploymentLocation() {
    return googleCloudProjectRegion;
  }
}
