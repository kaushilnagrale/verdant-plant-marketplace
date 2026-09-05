package com.verdant.assistant.web;

import com.verdant.assistant.service.KnowledgeIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** PLATFORM_ADMIN only, same manual-authorization style as PayoutAdminController/BackfillController - not a buyer-facing endpoint. */
@RestController
@RequestMapping("/v1/admin/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeIngestService ingest;

    public KnowledgeAdminController(KnowledgeIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingestAll(Authentication authentication) {
        requirePlatformAdmin(authentication);
        int documents = ingest.ingestAllFromClasspath();
        return Map.of("documentsIngested", documents);
    }

    private void requirePlatformAdmin(Authentication authentication) {
        boolean isPlatformAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));
        if (!isPlatformAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform ops only");
        }
    }
}
