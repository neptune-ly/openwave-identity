package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import ly.openwave.identity.security.callerBankHandle
import ly.openwave.identity.service.PortalBankLoginService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/identity/login-approvals")
class BankLoginApprovalController(
    private val portalBankLoginService: PortalBankLoginService
) {
    @GetMapping("/{challengeId}")
    fun get(@PathVariable challengeId: String): Map<String, Any?> =
        portalBankLoginService.getForBank(
            bankHandle = callerBankHandle(),
            challengeId = challengeId
        )

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false, defaultValue = "25") limit: Int
    ): Map<String, Any?> =
        portalBankLoginService.listForBank(
            bankHandle = callerBankHandle(),
            status = status,
            search = search,
            limit = limit
        )

    @GetMapping("/pending")
    fun pending(@RequestParam customerRef: String): Map<String, Any?> =
        mapOf(
            "data" to portalBankLoginService.listPending(
                bankHandle = callerBankHandle(),
                bankCustomerRef = customerRef.trim()
            )
        )

    @PostMapping("/{challengeId}/approve")
    fun approve(
        @PathVariable challengeId: String,
        @Valid @RequestBody req: BankLoginApprovalActionRequest
    ): Map<String, Any?> {
        val result = portalBankLoginService.approve(
            challengeId = challengeId,
            bankHandle = callerBankHandle(),
            bankCustomerRef = req.customerRef.trim()
        )
        return mapOf(
            "challenge_id" to result.challengeId,
            "status" to result.status,
            "expires_in" to result.expiresIn,
            "bank_handle" to result.bankHandle
        )
    }

    @PostMapping("/{challengeId}/reject")
    fun reject(
        @PathVariable challengeId: String,
        @Valid @RequestBody req: BankLoginApprovalActionRequest
    ): Map<String, Any?> {
        val result = portalBankLoginService.reject(
            challengeId = challengeId,
            bankHandle = callerBankHandle(),
            bankCustomerRef = req.customerRef.trim()
        )
        return mapOf(
            "challenge_id" to result.challengeId,
            "status" to result.status,
            "expires_in" to result.expiresIn,
            "bank_handle" to result.bankHandle
        )
    }
}

data class BankLoginApprovalActionRequest(
    @field:NotBlank val customerRef: String
)
