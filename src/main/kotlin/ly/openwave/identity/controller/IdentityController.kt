package ly.openwave.identity.controller

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Digits
import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.security.callerBankHandle
import ly.openwave.identity.service.IdentityService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/identity")
class IdentityController(
    private val identityService: IdentityService,
    private val registryProperties: RegistryProperties
) {

    @PostMapping("/claim")
    fun claim(@Valid @RequestBody req: ClaimHandleRequest): ResponseEntity<IdentityResponse> {
        val callerBank = callerBankHandle()
        val wasNew = identityService.getIdentityOrNull(req.nptHandle) == null
        val result = identityService.claimHandle(
            nptHandle       = req.nptHandle,
            bankHandle      = callerBank,
            iban            = req.iban,
            displayName     = req.customerDisplayName,
            bankCustomerRef = req.bankCustomerRef,
            setAsDefault    = req.setAsDefault ?: true,
            nationalId      = req.nationalId,
            phone           = req.phone,
            email           = req.customerEmail
        )
        return ResponseEntity.status(if (wasNew) HttpStatus.CREATED else HttpStatus.OK)
            .body(result.identity.toResponse(result.customerPortalAccess))
    }

    @GetMapping("/{nptHandle}")
    fun getProfile(@PathVariable nptHandle: String): PublicProfileResponse {
        val identity = identityService.getIdentity(nptHandle)
        val accountCount = identityService.getLinkedAccounts(nptHandle, identity.linkedAccounts.firstOrNull()?.bankHandle ?: "").size
        return identity.toPublicProfile(accountCount)
    }

    @DeleteMapping("/{nptHandle}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteIdentity(@PathVariable nptHandle: String) {
        identityService.deleteIdentity(nptHandle, callerBankHandle())
    }

    @GetMapping("/{nptHandle}/accounts")
    fun listAccounts(@PathVariable nptHandle: String): LinkedAccountsResponse {
        val accounts = identityService.getLinkedAccounts(nptHandle, callerBankHandle())
        return LinkedAccountsResponse(
            nptHandle = nptHandle,
            accounts  = accounts.map { it.toResponse() }
        )
    }

    @GetMapping("/accounts")
    fun listCallerBankAliases(
        @RequestParam(required = false, defaultValue = "true") activeOnly: Boolean,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false, defaultValue = "json") format: String
    ): ResponseEntity<Any> {
        val bankHandle = callerBankHandle()
        val csvExport = format.equals("csv", ignoreCase = true)
        val identities = identityService.searchAliasesForBank(
            callerBankHandle = bankHandle,
            activeOnly = activeOnly,
            search = search,
            page = page,
            limit = limit,
            maxLimit = if (csvExport) 1_000 else 100
        )
        val aliases = identities.content.map { it.toBankAliasResponse(bankHandle) }
        if (csvExport) {
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openwave-identity-bank-aliases.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(bankAliasesCsv(aliases))
        }
        return ResponseEntity.ok(BankAliasesResponse(
            bankHandle = bankHandle,
            total = identities.totalElements,
            page = identities.number,
            limit = identities.size,
            totalPages = identities.totalPages,
            aliases = aliases
        ))
    }

    @PostMapping("/{nptHandle}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun linkAccount(
        @PathVariable nptHandle: String,
        @Valid @RequestBody req: LinkAccountRequest
    ): LinkedAccountResponse {
        return identityService.linkAccount(
            nptHandle       = nptHandle,
            bankHandle      = callerBankHandle(),
            iban            = req.iban,
            bankCustomerRef = req.bankCustomerRef,
            setAsDefault    = req.setAsDefault ?: false
        ).toResponse()
    }

    @PatchMapping("/accounts/{accountId}/set-default")
    fun setDefaultAccountById(@PathVariable accountId: Long): LinkedAccountResponse =
        identityService.setDefaultAccountById(accountId, callerBankHandle()).toResponse()

    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlinkAccountById(@PathVariable accountId: Long) {
        identityService.unlinkAccountById(accountId, callerBankHandle())
    }

    @GetMapping("/{nptHandle}/accounts/{bankHandle}")
    fun listBankAccounts(
        @PathVariable nptHandle: String,
        @PathVariable bankHandle: String
    ): LinkedAccountsResponse {
        val accounts = identityService.getLinkedAccountsForBank(nptHandle, bankHandle, callerBankHandle())
        return LinkedAccountsResponse(nptHandle = nptHandle, accounts = accounts.map { it.toResponse() })
    }

    @PatchMapping("/{nptHandle}/accounts/iban/{iban}")
    fun updateAccount(
        @PathVariable nptHandle: String,
        @PathVariable iban: String,
        @Valid @RequestBody req: UpdateAccountRequest
    ): LinkedAccountResponse {
        return identityService.updateLinkedAccount(
            nptHandle        = nptHandle,
            iban             = iban,
            bankHandle       = req.bankHandle,
            callerBankHandle = callerBankHandle(),
            newIban          = req.newIban
        ).toResponse()
    }

    @DeleteMapping("/{nptHandle}/accounts/iban/{iban}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlinkAccount(
        @PathVariable nptHandle: String,
        @PathVariable iban: String,
        @RequestParam bankHandle: String
    ) {
        identityService.unlinkAccount(nptHandle, iban, bankHandle, callerBankHandle())
    }

    @PatchMapping("/{nptHandle}/accounts/iban/{iban}/set-default")
    fun setDefaultIban(
        @PathVariable nptHandle: String,
        @PathVariable iban: String,
        @RequestParam bankHandle: String
    ): LinkedAccountResponse {
        return identityService.setDefaultIban(nptHandle, iban, bankHandle, callerBankHandle()).toResponse()
    }

    @PatchMapping("/{nptHandle}/default-bank")
    fun setDefaultBank(
        @PathVariable nptHandle: String,
        @Valid @RequestBody req: SetDefaultBankRequest
    ): SetDefaultResponse {
        val identity = identityService.setDefaultBank(nptHandle, req.bankHandle, callerBankHandle())
        return SetDefaultResponse(
            nptHandle         = identity.nptHandle,
            defaultBankHandle = identity.defaultBankHandle,
            updatedAt         = identity.updatedAt
        )
    }

    @GetMapping("/internal/phone-lookup")
    fun lookupByPhone(
        @RequestHeader("X-OpenWave-Registry-Key", required = false) registryKey: String?,
        @RequestParam phone: String
    ): PhoneLookupResponse {
        if (registryProperties.adminKey.isBlank() || registryKey != registryProperties.adminKey) {
            throw org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Server-to-server registry key required"
            )
        }
        val identity = identityService.getIdentityByPhone(phone)
        val accounts = identity.linkedAccounts
            .filter { identity.status == ly.openwave.identity.entity.IdentityStatus.ACTIVE }
            .sortedWith(compareByDescending<LinkedAccountEntity> { it.isDefault }.thenBy { it.id })
        return PhoneLookupResponse(
            resolved = accounts.isNotEmpty(),
            nptHandle = identity.nptHandle,
            displayName = identity.displayName,
            phoneMasked = maskPhone(identity.phone),
            accounts = accounts.map {
                PhoneLookupAccountResponse(
                    alias = "${identity.nptHandle}@${it.bankHandle}",
                    aliasUsername = identity.nptHandle,
                    bankHandle = it.bankHandle,
                    iban = it.iban,
                    ibanMasked = maskIban(it.iban),
                    accountName = it.displayName,
                    currency = it.currency,
                    isDefault = it.isDefault,
                    linkedAt = it.linkedAt
                )
            }
        )
    }
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class ClaimHandleRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[a-z0-9_.\\-]{3,32}$", message = "Handle must be 3-32 lowercase alphanumeric characters, dots, underscores, or hyphens")
    val nptHandle: String,

    @field:NotBlank val iban: String,
    @field:NotBlank @field:Size(min = 2, max = 100) val customerDisplayName: String,
    @field:NotBlank val bankCustomerRef: String,
    val setAsDefault: Boolean? = true,

    @field:Pattern(regexp = "^[0-9]{12}$", message = "National ID must be exactly 12 digits")
    val nationalId: String? = null,

    @field:Pattern(regexp = "^[0-9+\\-]{7,20}$", message = "Invalid phone number format")
    @JsonAlias("customer_phone")
    val phone: String? = null,

    @field:NotBlank(message = "Customer email is required")
    @field:Email
    @JsonAlias("customer_email")
    val customerEmail: String? = null
)

data class LinkAccountRequest(
    @field:NotBlank val iban: String,
    @field:NotBlank val bankCustomerRef: String,
    val setAsDefault: Boolean? = false
)

data class UpdateAccountRequest(
    @field:NotBlank val bankHandle: String,
    @field:NotBlank val newIban: String
)

data class SetDefaultBankRequest(@field:NotBlank val bankHandle: String)

data class BankAliasesResponse(
    val bankHandle: String,
    val total: Long,
    val page: Int = 0,
    val limit: Int = 100,
    val totalPages: Int = 1,
    val aliases: List<BankAliasResponse>
)

data class BankAliasResponse(
    val alias: String,
    val aliasUsername: String,
    val fullAlias: String,
    val customerId: String,
    val customerPhone: String?,
    val isActive: Boolean,
    val enrolledAt: Instant,
    val accounts: List<BankAliasAccountResponse>
)

data class BankAliasAccountResponse(
    val accountId: Long,
    val iban: String,
    val ibanMasked: String,
    val accountName: String?,
    val currency: String,
    val isDefault: Boolean
)

data class IdentityResponse(
    val nptHandle: String,
    val displayName: String,
    val status: String,
    val defaultBankHandle: String?,
    val linkedBanks: List<String>,
    val nationalIdPresent: Boolean,
    val customerPortalAccess: CustomerPortalAccessResponse?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CustomerPortalAccessResponse(
    val username: String,
    val userCreated: Boolean,
    val emailConfigured: Boolean,
    val passwordSetupLinkIssued: Boolean,
    val nextStep: String
)

data class PublicProfileResponse(
    val nptHandle: String,
    val displayName: String,
    val hasDefault: Boolean,
    val linkedBankCount: Int,
    val status: String
)

data class LinkedAccountsResponse(
    val nptHandle: String,
    val accounts: List<LinkedAccountResponse>
)

data class LinkedAccountResponse(
    val bankHandle: String,
    val iban: String,
    val ibanMasked: String,
    val displayName: String?,
    val currency: String,
    val isDefault: Boolean,
    val linkedAt: Instant
)

data class SetDefaultResponse(
    val nptHandle: String,
    val defaultBankHandle: String?,
    val updatedAt: Instant
)

data class PhoneLookupResponse(
    val resolved: Boolean,
    val nptHandle: String,
    val displayName: String,
    val phoneMasked: String?,
    val accounts: List<PhoneLookupAccountResponse>
)

data class PhoneLookupAccountResponse(
    val alias: String,
    val aliasUsername: String,
    val bankHandle: String,
    val iban: String,
    val ibanMasked: String,
    val accountName: String?,
    val currency: String,
    val isDefault: Boolean,
    val linkedAt: Instant
)

// ─── Mappers ─────────────────────────────────────────────────────────────────

fun IdentityEntity.toResponse(customerPortalAccess: ly.openwave.identity.service.CustomerPortalAccessResult? = null) = IdentityResponse(
    nptHandle         = nptHandle,
    displayName       = displayName,
    status            = status.name,
    defaultBankHandle = defaultBankHandle,
    linkedBanks       = linkedAccounts.map { it.bankHandle }.distinct(),
    nationalIdPresent = nationalId != null,
    customerPortalAccess = customerPortalAccess?.let {
        CustomerPortalAccessResponse(
            username = it.username,
            userCreated = it.userCreated,
            emailConfigured = it.emailConfigured,
            passwordSetupLinkIssued = it.passwordSetupLinkIssued,
            nextStep = it.nextStep
        )
    },
    createdAt         = createdAt,
    updatedAt         = updatedAt
)

private fun IdentityEntity.toBankAliasResponse(bankHandle: String): BankAliasResponse {
    val bankAccounts = linkedAccounts
        .filter { it.bankHandle == bankHandle }
        .sortedWith(compareByDescending<LinkedAccountEntity> { it.isDefault }.thenBy { it.id })
    return BankAliasResponse(
        alias = "$nptHandle@$bankHandle",
        aliasUsername = nptHandle,
        fullAlias = "$nptHandle@$bankHandle",
        customerId = bankAccounts.firstOrNull()?.bankCustomerRef ?: "",
        customerPhone = phone,
        isActive = status == ly.openwave.identity.entity.IdentityStatus.ACTIVE,
        enrolledAt = bankAccounts.firstOrNull()?.linkedAt ?: createdAt,
        accounts = bankAccounts.map { account ->
            BankAliasAccountResponse(
                accountId = account.id,
                iban = account.iban,
                ibanMasked = maskIban(account.iban),
                accountName = account.displayName,
                currency = account.currency,
                isDefault = account.isDefault
            )
        }
    )
}

fun IdentityEntity.toPublicProfile(accountCount: Int) = PublicProfileResponse(
    nptHandle        = nptHandle,
    displayName      = displayName,
    hasDefault       = defaultBankHandle != null,
    linkedBankCount  = accountCount,
    status           = status.name
)

fun LinkedAccountEntity.toResponse() = LinkedAccountResponse(
    bankHandle  = bankHandle,
    iban        = iban,
    ibanMasked  = maskIban(iban),
    displayName = displayName,
    currency    = currency,
    isDefault   = isDefault,
    linkedAt    = linkedAt
)

private fun maskIban(iban: String): String =
    if (iban.length <= 10) iban
    else "${iban.take(6)}...${iban.takeLast(4)}"

private fun maskPhone(phone: String?): String? {
    val digits = phone?.filter(Char::isDigit)?.takeIf { it.isNotBlank() } ?: return null
    return if (digits.length <= 4) "***" else "***${digits.takeLast(4)}"
}

private fun bankAliasesCsv(rows: List<BankAliasResponse>): String {
    val lines = mutableListOf(
        listOf(
            "alias",
            "alias_username",
            "customer_ref",
            "phone_masked",
            "status",
            "enrolled_at",
            "account_count",
            "account_ids",
            "account_names",
            "currencies",
            "default_account_ids",
            "iban_masked"
        ).joinToString(",")
    )
    rows.forEach { row ->
        lines += listOf(
            row.fullAlias,
            row.aliasUsername,
            maskSensitiveReference(row.customerId),
            maskPhone(row.customerPhone),
            if (row.isActive) "ACTIVE" else "INACTIVE",
            row.enrolledAt,
            row.accounts.size,
            row.accounts.joinToString("|") { it.accountId.toString() },
            row.accounts.joinToString("|") { it.accountName ?: "" },
            row.accounts.joinToString("|") { it.currency },
            row.accounts.filter { it.isDefault }.joinToString("|") { it.accountId.toString() },
            row.accounts.joinToString("|") { it.ibanMasked }
        ).joinToString(",") { csvCell(it) }
    }
    return lines.joinToString("\n") + "\n"
}

private fun csvCell(value: Any?): String {
    val text = value?.toString() ?: ""
    val escaped = text.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun maskSensitiveReference(value: String?): String {
    val text = value ?: return ""
    return Regex("LY[0-9A-Z]{13,32}", RegexOption.IGNORE_CASE)
        .replace(text) { match -> maskIban(match.value.uppercase()) }
}
