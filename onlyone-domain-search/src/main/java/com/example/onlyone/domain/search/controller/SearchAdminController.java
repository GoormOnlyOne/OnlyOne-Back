package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.search.service.ClubElasticsearchService;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/search")
@Profile({"loadtest", "local"})
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchAdminController {

    private final ClubElasticsearchService clubElasticsearchService;

    @PostMapping("/reindex")
    public ResponseEntity<?> reindexAll() {
        clubElasticsearchService.reindexAll();
        return ResponseEntity.ok(CommonResponse.success("Reindex started (async)"));
    }
}
