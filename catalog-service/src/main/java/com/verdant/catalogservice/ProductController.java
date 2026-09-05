package com.verdant.catalogservice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * No auth wired in this module at all yet - a real cutover would put
 * this behind the same OAuth2/Keycloak boundary as the monolith (and
 * likely a service mesh / internal network boundary instead of public
 * exposure for a service-to-service read like this one), which is real,
 * separate scope this proof-of-concept doesn't claim to have done -
 * see DECISIONS.md.
 */
@RestController
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping("/v1/catalog-service/products/{id}")
    public ProductRepository.ProductSummary getProduct(@PathVariable UUID id) {
        return products.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product"));
    }

    @GetMapping("/v1/catalog-service/products")
    public List<ProductRepository.ProductSummary> search(@RequestParam String q) {
        return products.search(q, 20);
    }

    @GetMapping("/v1/catalog-service/health")
    @ResponseStatus(HttpStatus.OK)
    public void health() { }
}
