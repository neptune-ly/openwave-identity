package ly.openwave.identity.service

import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.exception.HandleRetiredException
import ly.openwave.identity.exception.IdentityNotFoundException
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import org.springframework.stereotype.Service
import java.time.Instant

data class AliasResolution(
    val nptHandle: String,
    val bankHandle: String,
    val iban: String,
    val displayName: String,
    val isDefault: Boolean,
    val resolvedAt: Instant = Instant.now()
)

@Service
class ResolutionService(
    private val identityRepo: IdentityRepository,
    private val linkedAccountRepo: LinkedAccountRepository,
    private val retiredHandleRepo: ly.openwave.identity.repository.RetiredHandleRepository
) {
    fun resolve(alias: String): AliasResolution {
        val (handle, bankHint) = parseAlias(alias)

        val identity = identityRepo.findByNptHandle(handle)
            ?: run {
                // A RETIRED NAME IS NOT A TYPO, AND SAYING SO MATTERS.
                //
                // Someone paying a still-circulating old handle — a saved payee,
                // a printed QR, an address pasted into a message — needs to know
                // the name MOVED, not that they mistyped. Those are different
                // next actions, and collapsing them means retrying the same
                // wrong address until they give up.
                //
                // It deliberately does not name the successor. Forwarding would
                // defeat renaming for a customer who wanted to stop being
                // reachable, and would let anyone discover the new handle by
                // paying the old one.
                if (retiredHandleRepo.existsByHandle(handle)) throw HandleRetiredException(handle)
                throw IdentityNotFoundException(alias)
            }

        if (identity.status != IdentityStatus.ACTIVE)
            throw IdentityNotFoundException(alias)

        val targetBank = bankHint ?: identity.defaultBankHandle
            ?: throw IdentityNotFoundException("$alias (no default account set)")

        // Pick the default IBAN for this bank; fall back to first if none marked default
        val bankAccounts = linkedAccountRepo.findAllByIdentityIdAndBankHandle(identity.id, targetBank)
        if (bankAccounts.isEmpty()) throw IdentityNotFoundException("$alias (no account linked for bank '$targetBank')")
        val link = bankAccounts.firstOrNull { it.isDefault } ?: bankAccounts.first()

        return AliasResolution(
            nptHandle   = identity.nptHandle,
            bankHandle  = link.bankHandle,
            iban        = link.iban,
            displayName = identity.displayName,
            isDefault   = link.bankHandle == identity.defaultBankHandle
        )
    }

    private fun parseAlias(alias: String): Pair<String, String?> {
        val parts = alias.split("@")
        return when (parts.size) {
            1 -> Pair(parts[0].lowercase(), null)
            2 -> Pair(parts[0].lowercase(), parts[1].lowercase())
            else -> throw IdentityNotFoundException(alias)
        }
    }
}
