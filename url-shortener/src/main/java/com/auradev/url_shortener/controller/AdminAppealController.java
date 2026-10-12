package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.ResolveAppealRequest;
import com.auradev.url_shortener.dto.response.AdminAppealResponse;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.service.AdminAppealService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin xem xét kháng nghị của chủ link bị chặn.
 *
 * <p>Endpoints (base path {@code /api/v1/admin/appeals}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>GET {@code /}              — Danh sách kháng nghị (lọc theo status, mặc định OPEN)</li>
 *   <li>PUT {@code /{id}/accept}   — Chấp nhận: gỡ chặn link</li>
 *   <li>PUT {@code /{id}/reject}   — Từ chối, kèm lời phản hồi tuỳ chọn (body {@code {"response": "..."}})</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN + "/appeals")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAppealController {

    private final AdminAppealService adminAppealService;
    private final TranslatorUtils    translator;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminAppealResponse>>> list(
            @RequestParam(defaultValue = "OPEN") AppealStatusEnum status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AppConstant.DEFAULT_PAGE_SIZE) int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), AppConstant.MAX_PAGE_SIZE));
        return ResponseEntity.ok(ApiResponse.success(null, adminAppealService.list(status, pageable)));
    }

    @PutMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<AdminAppealResponse>> accept(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(
                translator.toLocale("api.success.appeal_accepted"), adminAppealService.accept(id)));
    }

    @PutMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<AdminAppealResponse>> reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ResolveAppealRequest body
    ) {
        String response = body == null ? null : body.getResponse();
        return ResponseEntity.ok(ApiResponse.success(
                translator.toLocale("api.success.appeal_rejected"), adminAppealService.reject(id, response)));
    }
}
