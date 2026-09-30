package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core.account.domain.Account
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramConditionCheckResponse
import ai.govbiz.core.supportprogram.controller.validation.CodePointMax
import ai.govbiz.core.supportprogram.service.conditioncheck.SupportProgramConditionCheckService
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인한 회원의 기업 프로필로 공고 분석 조건을 확인합니다. 세션이 없으면 401이고, 회원별 결과라 캐시하지 않습니다.
 * 공고 식별 규칙과 없는·미노출 공고의 404 `SUPPORT_PROGRAM_NOT_FOUND`는 상세 조회와 같습니다.
 */
@RestController
@RequestMapping("/api/v1/me/support-programs")
class SupportProgramConditionCheckController(
    private val conditionCheckService: SupportProgramConditionCheckService,
) {

    @GetMapping("/condition-check")
    fun check(
        account: Account,
        @RequestParam
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
        sourceCode: String,
        @RequestParam
        @NotBlank
        @CodePointMax(max = 255)
        @Pattern(regexp = "(?Us)^(?!\\s)(?!.*\\s$)(?!.*\\p{C}).+$")
        sourceProgramId: String,
    ): ResponseEntity<SupportProgramConditionCheckResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            SupportProgramConditionCheckResponse.from(conditionCheckService.check(account.id, sourceCode, sourceProgramId)),
        )
}
