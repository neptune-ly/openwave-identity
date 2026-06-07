package ly.openwave.identity.controller

import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.security.PortalTokenService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/customer")
class CustomerPortalController(
    private val portalTokenService: PortalTokenService,
    private val identityRepository: IdentityRepository
) {
    @GetMapping("/aliases")
    fun aliases(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any?> {
        val principal = portalTokenService.verify(session)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        if (principal.role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        val identity = identityRepository.findByNptHandle(principal.subject)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Customer identity was not found")
        return mapOf(
            "npt_handle" to identity.nptHandle,
            "display_name" to identity.displayName,
            "status" to identity.status.name,
            "default_bank_handle" to identity.defaultBankHandle,
            "active" to (identity.status == IdentityStatus.ACTIVE),
            "phone_masked" to maskPhone(identity.phone),
            "email_masked" to maskEmail(identity.email),
            "accounts" to identity.linkedAccounts.sortedWith(compareBy({ it.bankHandle }, { it.id })).map {
                mapOf(
                    "bank_handle" to it.bankHandle,
                    "alias" to "${identity.nptHandle}@${it.bankHandle}",
                    "iban_masked" to maskIban(it.iban),
                    "account_name" to it.displayName,
                    "currency" to it.currency,
                    "default" to it.isDefault,
                    "linked_at" to it.linkedAt
                )
            }
        )
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
