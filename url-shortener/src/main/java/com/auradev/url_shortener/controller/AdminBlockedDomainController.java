package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.AddBlockedDomainRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.BlockedDomainResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.service.BlockedDomainService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin quản lý blocklist domain.
 *
 * <p>Endpoints (base path {@code /api/v1/admin/blocked-domains}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>POST   {@code /}     — Thêm domain (tuỳ chọn chặn luôn các link hiện có)</li>
 *   <li>GET    {@code /}     — Danh sách (phân trang, tìm theo từ khoá)</li>
 *   <li>DELETE {@code /{id}} — Xoá domain khỏi blocklist</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN + "/blocked-domains")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBlockedDomainController {

    private final BlockedDomainService blockedDomainService;
    private final TranslatorUtils      translator;

    @PostMapping
    public ResponseEntity<ApiResponse<BlockedDomainResponse>> add(@Valid @RequestBody AddBlockedDomainRequest body) {
        BlockedDomainResponse created = blockedDomainService.add(body);
        String message = translator.toLocale("api.success.blocked_domain_added");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<BlockedDomainResponse>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AppConstant.DEFAULT_PAGE_SIZE) int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), AppConstant.MAX_PAGE_SIZE),
                Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.success(null, blockedDomainService.list(keyword, pageable)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable Long id) {
        blockedDomainService.remove(id);
        String message = translator.toLocale("api.success.blocked_domain_removed");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }
}
