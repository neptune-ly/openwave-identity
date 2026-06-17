package ly.openwave.identity.controller

import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.security.PortalTokenService
import ly.openwave.identity.service.PortalBankLoginService
import ly.openwave.identity.service.PortalSecurityService
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/customer")
class CustomerPortalController(
    private val portalTokenService: PortalTokenService,
    private val identityRepository: IdentityRepository,
    private val bankRepository: BankRepository,
    private val portalSecurityService: PortalSecurityService,
    private val portalBankLoginService: PortalBankLoginService
) {
    @GetMapping("/aliases")
    @Transactional(readOnly = true)
    fun aliases(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any?> {
        val principal = portalTokenService.verify(session)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        if (principal.role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        val identity = identityRepository.findByNptHandle(principal.subject)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Customer identity was not found")
        val portalUser = portalSecurityService.findUser(identity.nptHandle)
        val passkeyCount = portalUser?.let(portalSecurityService::passkeyCount) ?: 0
        val totpEnabled = !portalUser?.totpSecret.isNullOrBlank()
        val totpPending = !portalUser?.totpPendingSecret.isNullOrBlank()
        val linkedBanks = identity.linkedAccounts.map { it.bankHandle }.distinct().sorted()
        val bankDisplayByHandle = identity.linkedAccounts
            .map { it.bankHandle }
            .distinct()
            .associateWith { handle -> bankRepository.findByBankHandle(handle)?.displayName ?: handle }
        val nextSteps = buildList {
            if (identity.linkedAccounts.isEmpty()) add("Ask your bank to complete the first account link before relying on this identity for payments.")
            if (identity.defaultBankHandle.isNullOrBlank()) add("Ask a linked bank or the registry operator to assign a default bank route for bare NPT handle resolution.")
            if (portalUser?.email.isNullOrBlank()) add("Add a recovery email in Security so password reset and account recovery stay available.")
            if (passkeyCount == 0) add("Register a passkey on a trusted device for stronger daily sign-in.")
            if (!totpEnabled) add(if (totpPending) "Finish authenticator setup with the pending code confirmation." else "Enable authenticator codes as a recovery factor for cross-device access.")
            if (linkedBanks.isNotEmpty()) add("Use one of your linked bank apps to approve public-identifier sign-ins that start from phone number or national ID.")
        }
        val readinessChecks = listOf(
            mapOf("key" to "linked_accounts", "label" to "Linked accounts", "done" to identity.linkedAccounts.isNotEmpty()),
            mapOf("key" to "default_bank", "label" to "Default bank route", "done" to !identity.defaultBankHandle.isNullOrBlank()),
            mapOf("key" to "recovery_email", "label" to "Recovery email", "done" to !portalUser?.email.isNullOrBlank()),
            mapOf("key" to "passkey", "label" to "Passkey", "done" to (passkeyCount > 0)),
            mapOf("key" to "authenticator", "label" to "Authenticator", "done" to totpEnabled)
        )
        val readinessDone = readinessChecks.count { it["done"] == true }
        return mapOf(
            "npt_handle" to identity.nptHandle,
            "display_name" to identity.displayName,
            "status" to identity.status.name,
            "default_bank_handle" to identity.defaultBankHandle,
            "active" to (identity.status == IdentityStatus.ACTIVE),
            "national_id" to identity.nationalId,
            "phone" to identity.phone,
            "phone_masked" to maskPhone(identity.phone),
            "email" to identity.email,
            "email_masked" to maskEmail(identity.email),
            "created_at" to identity.createdAt,
            "updated_at" to identity.updatedAt,
            "package" to mapOf(
                "profile" to mapOf(
                    "active" to (identity.status == IdentityStatus.ACTIVE),
                    "status" to identity.status.name,
                    "display_name" to identity.displayName,
                    "npt_handle" to identity.nptHandle,
                    "default_bank_handle" to identity.defaultBankHandle,
                    "linked_bank_count" to linkedBanks.size,
                    "linked_account_count" to identity.linkedAccounts.size
                ),
                "access" to mapOf(
                    "direct_portal_sign_in" to mapOf(
                        "enabled" to (portalUser != null && portalUser.active),
                        "username" to portalUser?.username,
                        "email_login" to !portalUser?.email.isNullOrBlank(),
                        "last_login_at" to portalUser?.lastLoginAt
                    ),
                    "public_identifier_sign_in" to mapOf(
                        "phone_supported" to !identity.phone.isNullOrBlank(),
                        "national_id_supported" to !identity.nationalId.isNullOrBlank(),
                        "linked_bank_approval_required" to true,
                        "linked_bank_approval_available" to linkedBanks.isNotEmpty()
                    )
                ),
                "security" to mapOf(
                    "recovery_email_set" to !portalUser?.email.isNullOrBlank(),
                    "recovery_email" to portalUser?.email,
                    "passkey_count" to passkeyCount,
                    "totp_enabled" to totpEnabled,
                    "totp_pending" to totpPending,
                    "totp_enabled_at" to portalUser?.totpEnabledAt,
                    "last_login_at" to portalUser?.lastLoginAt,
                    "security_setup_required" to (portalUser?.active == true && passkeyCount == 0 && !totpEnabled),
                    "security_setup_reason" to when {
                        portalUser == null || !portalUser.active -> "Customer portal access is not active yet."
                        passkeyCount > 0 && totpEnabled -> null
                        totpPending -> "Authenticator setup is waiting for code confirmation."
                        passkeyCount == 0 && !totpEnabled -> "Portal access still depends on password recovery unless you add a passkey or authenticator."
                        else -> "Add another strong factor to improve recovery and day-to-day sign-in."
                    }
                ),
                "routing" to mapOf(
                    "bare_handle_route" to if (!identity.defaultBankHandle.isNullOrBlank()) "${identity.nptHandle} -> ${identity.defaultBankHandle}" else null,
                    "default_bank_handle" to identity.defaultBankHandle,
                    "qualified_alias_count" to identity.linkedAccounts.size,
                    "linked_banks" to linkedBanks.map { handle ->
                        mapOf(
                            "bank_handle" to handle,
                            "bank_display_name" to bankDisplayByHandle[handle],
                            "default_route" to (identity.defaultBankHandle == handle)
                        )
                    }
                ),
                "readiness" to mapOf(
                    "done" to readinessDone,
                    "total" to readinessChecks.size,
                    "checks" to readinessChecks
                ),
                "next_steps" to if (nextSteps.isNotEmpty()) nextSteps else listOf("Identity access and routing package is ready.")
            ),
            "accounts" to identity.linkedAccounts.sortedWith(compareBy({ it.bankHandle }, { it.id })).map {
                mapOf(
                    "account_id" to it.id,
                    "bank_handle" to it.bankHandle,
                    "bank_display_name" to bankDisplayByHandle[it.bankHandle],
                    "bank_customer_ref" to it.bankCustomerRef,
                    "alias" to "${identity.nptHandle}@${it.bankHandle}",
                    "iban" to it.iban,
                    "iban_masked" to maskIban(it.iban),
                    "account_name" to it.displayName,
                    "currency" to it.currency,
                    "default" to it.isDefault,
                    "linked_at" to it.linkedAt,
                    "updated_at" to it.updatedAt
                )
            }
        )
    }

    @GetMapping("/login-approvals")
    @Transactional(readOnly = true)
    fun loginApprovals(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "10") limit: Int
    ): Map<String, Any?> {
        val principal = portalTokenService.verify(session)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        if (principal.role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        val identity = identityRepository.findByNptHandle(principal.subject)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Customer identity was not found")
        return portalBankLoginService.listForCustomer(identity = identity, limit = limit, status = status)
    }

    @GetMapping("/login-approvals/{challengeId}")
    @Transactional(readOnly = true)
    fun loginApprovalDetail(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @PathVariable challengeId: String
    ): Map<String, Any?> {
        val principal = portalTokenService.verify(session)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        if (principal.role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        val identity = identityRepository.findByNptHandle(principal.subject)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Customer identity was not found")
        return portalBankLoginService.getForCustomer(identity = identity, challengeId = challengeId)
    }
}

private fun maskEmail(email: String?): String? {
    if (email.isNullOrBlank() || !email.contains("@")) return null
    val parts = email.split("@", limit = 2)
    return "${parts[0].take(2)}***@${parts[1]}"
}

private fun maskIban(iban: String): String =
    if (iban.length <= 10) iban else "${iban.take(6)}...${iban.takeLast(4)}"

private fun maskPhone(phone: String?): String? {
    val digits = phone?.filter(Char::isDigit)?.takeIf { it.isNotBlank() } ?: return null
    return if (digits.length <= 4) "***" else "***${digits.takeLast(4)}"
}
