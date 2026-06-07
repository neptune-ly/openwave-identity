package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.security.callerBankHandle
import ly.openwave.identity.service.BrandingAssetService
import ly.openwave.identity.service.BankService
import ly.openwave.identity.service.PortalAuditService
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
    private val auditService: PortalAuditService
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
        return bankService.getBank(bankHandle).toResponse()
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
        }.toResponse()

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
        }.toResponse()

    @PatchMapping("/me/branding")
    fun updateMyBankBranding(
        @RequestBody req: BankBrandingRequest,
        authentication: Authentication?
    ): BankResponse {
        val bankHandle = callerBankHandle()
        return bankService.updateBank(
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
        }.toResponse()
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
        return updated.toResponse()
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
        return updated.toResponse()
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

fun BankEntity.toResponse() = BankResponse(
    bankHandle   = bankHandle,
    displayName  = displayName,
    country      = country,
    coreUrl      = coreUrl,
    contactEmail = contactEmail,
    branding     = bankBranding(),
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
