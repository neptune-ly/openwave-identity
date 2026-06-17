package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.entity.BankLoginChallengeStatus
import ly.openwave.identity.security.callerBankHandle
import ly.openwave.identity.service.BrandingAssetService
import ly.openwave.identity.service.BankService
import ly.openwave.identity.service.PortalAuditService
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalBankLoginChallengeRepository
import ly.openwave.identity.repository.PortalUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

@RestController
@RequestMapping("/banks")
class BankController(
    private val bankService: BankService,
    private val brandingAssetService: BrandingAssetService,
    private val auditService: PortalAuditService,
    private val identityRepository: IdentityRepository,
    private val linkedAccountRepository: LinkedAccountRepository,
    private val portalUserRepository: PortalUserRepository,
    private val portalBankLoginChallengeRepository: PortalBankLoginChallengeRepository
) {

    @GetMapping
    fun listBanks(
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false, defaultValue = "true") activeOnly: Boolean
    ): BankListResponse {
        val banks = bankService.listBanks(country, activeOnly)
        return BankListResponse(
            banks       = banks.map { it.toPublicResponse() },
            total       = banks.size,
            generatedAt = Instant.now()
        )
    }

    @GetMapping("/{bankHandle}")
    fun getBank(@PathVariable bankHandle: String): BankPublicResponse =
        bankService.getBank(bankHandle).toPublicResponse()

    @GetMapping("/me")
    fun getMyBank(): BankResponse {
        val bankHandle = callerBankHandle()
        val bank = bankService.getBank(bankHandle)
        return bank.toResponse(operationsPackage = operationsPackage(bank))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun registerBank(@Valid @RequestBody req: RegisterBankRequest, authentication: Authentication?): BankRegistrationResponse {
        val result = bankService.registerBank(
            bankHandle   = req.bankHandle,
            displayName  = req.displayName,
            country      = req.country,
            coreUrl      = req.coreUrl,
            contactEmail = req.contactEmail
        )
        auditService.record(authentication, "BANK_CREATED", "BANK", result.bank.bankHandle)
        return BankRegistrationResponse(
            bankHandle   = result.bank.bankHandle,
            displayName  = result.bank.displayName,
            bankApiKey   = result.rawApiKey,
            portalUsername = result.portalUsername,
            portalPassword = result.portalPassword,
            registeredAt = result.bank.registeredAt
        )
    }

    @PatchMapping("/{bankHandle}")
    fun updateBank(
        @PathVariable bankHandle: String,
        @Valid @RequestBody req: UpdateBankRequest,
        authentication: Authentication?
    ): BankResponse =
        bankService.updateBank(
            bankHandle   = bankHandle,
            coreUrl      = req.coreUrl,
            displayName  = req.displayName,
            contactEmail = req.contactEmail,
            active       = req.active,
            logoUrl      = req.logoUrl,
            brandColor   = req.brandColor,
            supportEmail = req.supportEmail,
            website      = req.website
        ).also {
            auditService.record(authentication, "BANK_UPDATED", "BANK", bankHandle)
        }.toResponse(operationsPackage = operationsPackage(bankService.getBank(bankHandle)))

    @PatchMapping("/{bankHandle}/branding")
    fun updateBankBranding(
        @PathVariable bankHandle: String,
        @RequestBody req: BankBrandingRequest,
        authentication: Authentication?
    ): BankResponse =
        bankService.updateBank(
            bankHandle = bankHandle,
            coreUrl = null,
            displayName = req.displayName,
            contactEmail = null,
            active = null,
            logoUrl = req.logoUrl,
            brandColor = req.brandColor,
            supportEmail = req.supportEmail,
            website = req.website
        ).also {
            auditService.record(authentication, "BANK_BRANDING_UPDATED", "BANK", bankHandle)
        }.toResponse(operationsPackage = operationsPackage(bankService.getBank(bankHandle)))

    @PatchMapping("/me/branding")
    fun updateMyBankBranding(
        @RequestBody req: BankBrandingRequest,
        authentication: Authentication?
    ): BankResponse {
        val bankHandle = callerBankHandle()
        val updated = bankService.updateBank(
            bankHandle = bankHandle,
            coreUrl = null,
            displayName = req.displayName,
            contactEmail = null,
            active = null,
            logoUrl = req.logoUrl,
            brandColor = req.brandColor,
            supportEmail = req.supportEmail,
            website = req.website
        ).also {
            auditService.record(authentication, "BANK_SELF_BRANDING_UPDATED", "BANK", bankHandle)
        }
        return updated.toResponse(operationsPackage = operationsPackage(updated))
    }

    @PostMapping("/{bankHandle}/branding/logo")
    fun uploadBankLogo(
        @PathVariable bankHandle: String,
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication?
    ): BankResponse {
        bankService.getBank(bankHandle)
        val logoUrl = brandingAssetService.storeBankLogo(bankHandle, file)
        val updated = bankService.updateBank(bankHandle, null, null, null, null, logoUrl = logoUrl)
        auditService.record(authentication, "BANK_LOGO_UPLOADED", "BANK", bankHandle, mapOf("logo_url" to logoUrl))
        return updated.toResponse(operationsPackage = operationsPackage(updated))
    }

    @PostMapping("/me/branding/logo")
    fun uploadMyBankLogo(
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication?
    ): BankResponse {
        val bankHandle = callerBankHandle()
        val logoUrl = brandingAssetService.storeBankLogo(bankHandle, file)
        val updated = bankService.updateBank(bankHandle, null, null, null, null, logoUrl = logoUrl)
        auditService.record(authentication, "BANK_SELF_LOGO_UPLOADED", "BANK", bankHandle, mapOf("logo_url" to logoUrl))
        return updated.toResponse(operationsPackage = operationsPackage(updated))
    }

    private fun operationsPackage(bank: BankEntity): Map<String, Any?> {
        val linkedAccounts = linkedAccountRepository.findAllByBankHandle(bank.bankHandle)
        val linkedIdentityIds = linkedAccounts.map { it.identity.id }.distinct()
        val portalUsers = portalUserRepository.findAllByBankHandle(bank.bankHandle)
        val approvalRows = portalBankLoginChallengeRepository.findForBank(
            bankHandle = bank.bankHandle,
            status = null,
            needle = null
        )
        val pendingApprovals = approvalRows.count { it.effectiveStatus() == BankLoginChallengeStatus.PENDING.name }
        val approvedApprovals = approvalRows.count { it.effectiveStatus() == BankLoginChallengeStatus.APPROVED.name }
        val rejectedApprovals = approvalRows.count { it.effectiveStatus() == BankLoginChallengeStatus.REJECTED.name }
        val expiredApprovals = approvalRows.count { it.effectiveStatus() == "EXPIRED" }
        val activeCustomerCount = linkedIdentityIds.count { identityId ->
            identityRepository.findById(identityId).orElse(null)?.status?.name == "ACTIVE"
        }
        val readinessChecks = listOf(
            mapOf("key" to "directory_identity", "label" to "Directory identity", "done" to bank.displayName.isNotBlank(), "detail" to bank.displayName),
            mapOf("key" to "operations_contact", "label" to "Operations contact", "done" to (!bank.contactEmail.isBlank() || !bank.supportEmail.isNullOrBlank()), "detail" to (bank.supportEmail ?: bank.contactEmail)),
            mapOf("key" to "core_routing_profile", "label" to "Core routing profile", "done" to bank.coreUrl.isNotBlank(), "detail" to bank.coreUrl),
            mapOf("key" to "brand_signal", "label" to "Brand signal", "done" to (!bank.logoUrl.isNullOrBlank() || !bank.brandColor.isNullOrBlank()), "detail" to (bank.logoUrl ?: bank.brandColor ?: "No logo or brand color")),
            mapOf("key" to "public_website", "label" to "Public website", "done" to !bank.website.isNullOrBlank(), "detail" to (bank.website ?: "Missing website"))
        )
        val readinessDone = readinessChecks.count { it["done"] == true }
        val nextSteps = buildList {
            if (!bank.active) add("Activate the bank record before relying on it for customer enrollment or approval queues.")
            if (bank.supportEmail.isNullOrBlank() && bank.contactEmail.isBlank()) add("Add a visible operations or support email before external rollout.")
            if (bank.logoUrl.isNullOrBlank() && bank.brandColor.isNullOrBlank()) add("Add a logo or brand color so partner and customer-facing pages show a clear bank signal.")
            if (bank.website.isNullOrBlank()) add("Add a public website for support-safe directory use.")
            if (linkedAccounts.isEmpty()) add("No linked customer accounts are registered under this bank yet.")
            if (portalUsers.none { it.active }) add("No active bank portal user is available for operational ownership.")
        }.ifEmpty {
            listOf("Bank identity operations package is ready.")
        }
        return mapOf(
            "profile" to mapOf(
                "active" to bank.active,
                "bank_handle" to bank.bankHandle,
                "display_name" to bank.displayName,
                "country" to bank.country
            ),
            "customer_registry" to mapOf(
                "linked_account_count" to linkedAccounts.size,
                "linked_customer_count" to linkedIdentityIds.size,
                "active_customer_count" to activeCustomerCount,
                "default_bank_route_count" to linkedAccounts.count { it.identity.defaultBankHandle == bank.bankHandle }
            ),
            "portal_access" to mapOf(
                "bank_portal_user_count" to portalUsers.size,
                "active_portal_user_count" to portalUsers.count { it.active },
                "admin_portal_user_count" to portalUsers.count { it.role.name == "BANK_ADMIN" }
            ),
            "login_approvals" to mapOf(
                "total" to approvalRows.size,
                "pending" to pendingApprovals,
                "approved" to approvedApprovals,
                "rejected" to rejectedApprovals,
                "expired" to expiredApprovals
            ),
            "trust" to mapOf(
                "bank_vouched_identity_participant" to bank.active,
                "customer_enrollment_visible" to true,
                "public_directory_profile_ready" to (readinessDone == readinessChecks.size),
                "bank_app_login_approval_visible" to true
            ),
            "readiness" to mapOf(
                "done" to readinessDone,
                "total" to readinessChecks.size,
                "checks" to readinessChecks
            ),
            "next_steps" to nextSteps
        )
    }
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class RegisterBankRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[a-z0-9-]{2,20}$", message = "Bank handle must be 2-20 lowercase alphanumeric or hyphen characters")
    val bankHandle: String,

    @field:NotBlank @field:Size(min = 2, max = 100) val displayName: String,
    @field:NotBlank @field:Size(min = 2, max = 2)   val country: String,
    @field:NotBlank                                  val coreUrl: String,
    @field:NotBlank @field:Email                     val contactEmail: String
)

data class UpdateBankRequest(
    val coreUrl: String?,
    val displayName: String?,
    @field:Email val contactEmail: String?,
    val active: Boolean?,
    val logoUrl: String? = null,
    val brandColor: String? = null,
    val supportEmail: String? = null,
    val website: String? = null
)

data class BankBrandingRequest(
    val logoUrl: String? = null,
    val brandColor: String? = null,
    val displayName: String? = null,
    val supportEmail: String? = null,
    val website: String? = null
)

data class BankResponse(
    val bankHandle: String,
    val displayName: String,
    val country: String,
    val coreUrl: String,
    val contactEmail: String,
    val branding: Map<String, Any?>,
    val operationsPackage: Map<String, Any?>? = null,
    val active: Boolean,
    val registeredAt: Instant
)

data class BankPublicResponse(
    val bankHandle: String,
    val displayName: String,
    val country: String,
    val branding: Map<String, Any?>,
    val active: Boolean,
    val registeredAt: Instant
)

data class BankListResponse(
    val banks: List<BankPublicResponse>,
    val total: Int,
    val generatedAt: Instant
)

data class BankRegistrationResponse(
    val bankHandle: String,
    val displayName: String,
    val bankApiKey: String,
    val portalUsername: String,
    val portalPassword: String,
    val registeredAt: Instant
)

fun BankEntity.toPublicResponse() = BankPublicResponse(
    bankHandle   = bankHandle,
    displayName  = displayName,
    country      = country,
    branding     = bankBranding(),
    active       = active,
    registeredAt = registeredAt
)

fun BankEntity.toResponse(operationsPackage: Map<String, Any?>? = null) = BankResponse(
    bankHandle   = bankHandle,
    displayName  = displayName,
    country      = country,
    coreUrl      = coreUrl,
    contactEmail = contactEmail,
    branding     = bankBranding(),
    operationsPackage = operationsPackage,
    active       = active,
    registeredAt = registeredAt
)

fun BankEntity.bankBranding() = mapOf(
    "logo_url" to logoUrl,
    "brand_color" to brandColor,
    "display_name" to displayName,
    "support_email" to (supportEmail ?: contactEmail),
    "website" to website
)
