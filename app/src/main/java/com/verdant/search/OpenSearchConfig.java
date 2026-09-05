package com.verdant.search;

import org.apache.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security plugin is disabled everywhere this project runs OpenSearch
 * (docker-compose.yml, SearchIntegrationTestBase) - local dev and this
 * project's own tests only, so a plain unauthenticated RestClient is
 * genuinely correct here, not a shortcut standing in for real auth.
 */
@Configuration
public class OpenSearchConfig {

    @Bean
    public OpenSearchClient openSearchClient(@Value("${verdant.search.opensearch-uri:http://localhost:9200}") String uri) {
        RestClient restClient = RestClient.builder(HttpHost.create(uri)).build();
        OpenSearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }
}
