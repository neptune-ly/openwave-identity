package ly.openwave.identity.service

import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.exception.*
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val HANDLE_REGEX = Regex("^[a-z0-9_.\\-]{3,32}$")

data class CustomerPortalAccessResult(
    val username: String,
    val userCreated: Boolean,
    val emailConfigured: Boolean,
    val passwordSetupLinkIssued: Boolean,
    val nextStep: String
)

/**
 * Why a name cannot be used, when it cannot.
 *
 * Three genuinely different answers with three different next actions: pick
 * another (TAKEN), pick another and it will never be free (RETIRED), fix the
 * spelling (INVALID). Collapsing them into one sentence is the exact defect this
 * area has been producing for months.
 */
enum class HandleAvailability { AVAILABLE, TAKEN, RETIRED, INVALID }

data class ClaimHandleResult(
    val identity: IdentityEntity,
    val customerPortalAccess: CustomerPortalAccessResult?
)

@Service
class IdentityService(
    private val identityRepo: IdentityRepository,
    private val linkedAccountRepo: LinkedAccountRepository,
    private val bankRepo: BankRepository,
    private val portalUserService: PortalUserService,
    private val retiredHandleRepo: ly.openwave.identity.repository.RetiredHandleRepository,
    private val portalSecurityService: PortalSecurityService,
    private val credentialNotificationService: PortalCredentialNotificationService
) {
    private val log = org.slf4j.LoggerFactory.getLogger(IdentityService::class.java)


    @Transactional
    fun claimHandle(
        nptHandle: String,
        bankHandle: String,
        iban: String,
        displayName: String,
        bankCustomerRef: String,
        setAsDefault: Boolean,
        nationalId: String? = null,
        phone: String? = null,
        email: String? = null
    ): ClaimHandleResult {
        if (!HANDLE_REGEX.matches(nptHandle)) throw HandleInvalidFormatException(nptHandle)

        // A RETIRED HANDLE IS NOT AVAILABLE, AND THIS IS WHERE THAT IS ENFORCED.
        //
        // Retirement lives in its own table precisely so it survives the row it
        // came from. If claim did not consult it, the reservation would be a
        // comment: the first person to ask for a renamed customer's old name
        // would get it, and every payee, QR and pasted address still pointing at
        // that name would start paying them.
        retiredHandleRepo.findByHandle(nptHandle)?.let { throw HandleRetiredException(nptHandle) }
        val normalizedPhone = normalizePhone(phone) ?: phone?.trim()?.takeIf { it.isNotBlank() }
        val normalizedEmail = email?.trim()?.takeIf { it.isNotBlank() } ?: throw CustomerEmailRequiredException()

        // CRITICAL: National ID is REQUIRED for cross-bank identity verification
        if (nationalId == null || !nationalId.matches(Regex("^[0-9]{12}$"))) {
            throw HandleInvalidFormatException("National ID is required and must be exactly 12 digits")
        }

        bankRepo.findByBankHandle(bankHandle) ?: throw BankNotFoundException(bankHandle)

        // STEP 1: Check if this National ID already has an identity (PRIMARY KEY)
        val existingByNationalId = identityRepo.findByNationalId(nationalId)
        if (existingByNationalId != null) {
            // Customer already exists with a different username!
            if (existingByNationalId.nptHandle != nptHandle) {
                throw HandleTakenException(
                    "Customer with National ID $nationalId already has username '${existingByNationalId.nptHandle}'. " +
                    "Please use existing username instead of '$nptHandle'."
                )
            }
            // Same national ID, same username - proceed to link account
            val existing = existingByNationalId
            
            // Identity exists — check if this IBAN is already linked (idempotent)
            if (linkedAccountRepo.existsByIdentityIdAndIban(existing.id, iban)) {
                return ClaimHandleResult(existing, ensureCustomerPortalAccess(existing, normalizedEmail))
            }

            // CRITICAL: Verify phone number matches if both provided
            if (existing.phone != null && normalizedPhone != null && normalizePhone(existing.phone) != normalizedPhone) {
                throw ForbiddenException(
                    "Phone number mismatch: handle '$nptHandle' has phone ${existing.phone} but bank provided $normalizedPhone. " +
                    "Both National ID AND phone must match for cross-bank enrollment."
                )
            }
            // Update phone if not set (national_id already verified above)
            if (existing.phone == null && normalizedPhone != null) {
                existing.phone = normalizedPhone
                existing.updatedAt = Instant.now()
            }
            if (existing.email.isNullOrBlank()) {
                existing.email = normalizedEmail
                existing.updatedAt = Instant.now()
            }

            val isFirstForBank = !linkedAccountRepo.existsByIdentityIdAndBankHandle(existing.id, bankHandle)
            val isDefaultForBank = isFirstForBank || setAsDefault

            if (setAsDefault || isFirstForBank) {
                linkedAccountRepo.clearBankDefaults(existing.id, bankHandle)
            }
            linkedAccountRepo.save(
                LinkedAccountEntity(
                    identity        = existing,
                    bankHandle      = bankHandle,
                    iban            = iban,
                    bankCustomerRef = bankCustomerRef,
                    isDefault       = isDefaultForBank
                )
            )
            notifyLinkedAccountChange(
                identity = existing,
                bankHandle = bankHandle,
                iban = iban,
                actionLabel = if (isFirstForBank) "New bank linked to your identity" else "New account linked to your identity"
            )
            if (setAsDefault && existing.defaultBankHandle == null) {
                existing.defaultBankHandle = bankHandle
            }
            existing.updatedAt = Instant.now()
            identityRepo.save(existing)
            return ClaimHandleResult(existing, ensureCustomerPortalAccess(existing, normalizedEmail))
        }

        // STEP 2: Check if username is taken by someone else
        val existingByHandle = identityRepo.findByNptHandle(nptHandle)
        if (existingByHandle != null) {
            // Username exists but different national ID - REJECT
            throw HandleTakenException(
                "Username '$nptHandle' is already taken by another customer (National ID: ${existingByHandle.nationalId}). " +
                "Please choose a different username."
            )
        }

        // Brand new identity — save with national_id and phone (both REQUIRED)
        val identity = IdentityEntity(
            nptHandle         = nptHandle,
            displayName       = displayName,
            defaultBankHandle = if (setAsDefault) bankHandle else null,
            nationalId        = nationalId,  // REQUIRED
            phone             = normalizedPhone,
            email             = normalizedEmail
        )
        identityRepo.save(identity)

        linkedAccountRepo.save(
            LinkedAccountEntity(
                identity        = identity,
                bankHandle      = bankHandle,
                iban            = iban,
                bankCustomerRef = bankCustomerRef,
                isDefault       = true   // first IBAN for this bank is always default
            )
        )
        notifyLinkedAccountChange(
            identity = identity,
            bankHandle = bankHandle,
            iban = iban,
            actionLabel = "Identity enrolled and first account linked"
        )
        return ClaimHandleResult(identity, ensureCustomerPortalAccess(identity, normalizedEmail))
    }


    // ── renaming ────────────────────────────────────────────────────────────

    /**
     * How long a customer must wait between renames.
     *
     * Every rename permanently removes a string from circulation, so the limit
     * is not about load — it is about a customer (or a script holding a bank's
     * key) cycling handles to squat names at the registry's expense, and about
     * probing which names are free by watching which renames succeed.
     */
    private val renameCooldown: java.time.Duration = java.time.Duration.ofDays(30)

    /** A lifetime cap, because a cooldown alone still permits twelve a year. */
    private val maxLifetimeRenames = 3

    /**
     * Changes a customer's handle, and retires the old one forever.
     *
     * ## What this fixes
     *
     * There was no rename path at all. [claimHandle] looks the customer up by
     * national ID and throws HandleTaken whenever the stored handle differs from
     * the requested one, so a customer's SECOND username was refused
     * permanently — the bank saved it, OpenWave refused it, and the app reported
     * a failure every time. That was reported as "username set in app still says
     * failed to add to OpenWave Identity", four fixes ago.
     *
     * ## The old handle is retired, not released
     *
     * An npt handle is a payment address. Releasing it would silently redirect
     * every saved payee, printed QR and pasted address to whoever claimed it
     * next. Retirement is permanent and it is NOT a redirect: the old name
     * resolves to an explicit refusal, never to the new one, because forwarding
     * would defeat the point for a customer who renamed to stop being reachable
     * and would let anyone discover the new handle by paying the old one.
     *
     * ## Who may do this
     *
     * A bank that actually serves this customer — proved by holding a linked
     * account for them — AND the national ID, which is the same proof-of-identity
     * bar [claimHandle] sets. A registered bank with no relationship to the
     * customer cannot rename them, which without the account check it could,
     * since every bank authenticates the same way.
     */
    @Transactional
    fun renameHandle(
        currentHandle: String,
        newHandle: String,
        bankHandle: String,
        nationalId: String
    ): IdentityEntity {
        val desired = newHandle.trim().lowercase()
        if (!HANDLE_REGEX.matches(desired)) throw HandleInvalidFormatException(desired)

        val identity = identityRepo.findByNptHandle(currentHandle.trim().lowercase())
            ?: throw IdentityNotFoundException(currentHandle)

        // Renaming to the name you already hold is a no-op, not an error. The
        // caller is often a retry, and burning a rename slot on a retry would
        // punish exactly the flaky-network case this whole feature exists for.
        if (identity.nptHandle == desired) return identity

        bankRepo.findByBankHandle(bankHandle) ?: throw BankNotFoundException(bankHandle)

        // The caller must SERVE this customer. Every bank authenticates the same
        // way, so without this any registered bank could rename any customer in
        // the network.
        if (!linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, bankHandle)) {
            throw HandleRenameNotPermittedException(
                "Bank '$bankHandle' holds no linked account for this identity."
            )
        }

        // The same proof-of-identity bar claim sets. A bank that serves the
        // customer still has to name them correctly.
        if (identity.nationalId.isNullOrBlank() || identity.nationalId != nationalId) {
            throw HandleRenameNotPermittedException(
                "National ID does not match the identity being renamed."
            )
        }

        if (identity.status != IdentityStatus.ACTIVE) {
            throw HandleRenameNotPermittedException("This identity is not active.")
        }

        // Is the new name free? BOTH tables, in this order — a live holder is
        // the commoner case and gives the more useful error.
        identityRepo.findByNptHandle(desired)?.let { throw HandleTakenException(desired) }
        retiredHandleRepo.findByHandle(desired)?.let { throw HandleRetiredException(desired) }

        if (identity.handleRenameCount >= maxLifetimeRenames) {
            throw HandleRenameTooSoonException(
                "This identity has already changed its name $maxLifetimeRenames times."
            )
        }
        identity.handleRenamedAt?.let { last ->
            val next = last.plus(renameCooldown)
            if (Instant.now().isBefore(next)) {
                throw HandleRenameTooSoonException(
                    "A name can be changed once every ${renameCooldown.toDays()} days. Next change allowed after $next."
                )
            }
        }

        val previous = identity.nptHandle

        // RETIRE FIRST. Both writes are in one transaction, so ordering does not
        // decide what survives a crash — but it decides what a UNIQUE violation
        // means. Reserving before moving means a duplicate here aborts with the
        // old handle still on the identity, rather than leaving a renamed
        // customer whose old name was never reserved.
        retiredHandleRepo.save(
            ly.openwave.identity.entity.RetiredHandleEntity(
                handle = previous,
                formerIdentityId = identity.id,
                replacedByHandle = desired,
                performedByBank = bankHandle
            )
        )

        identity.nptHandle = desired
        identity.handleRenamedAt = Instant.now()
        identity.handleRenameCount += 1
        identity.updatedAt = Instant.now()
        identityRepo.save(identity)

        // THE CUSTOMER'S PORTAL LOGIN IS THE HANDLE.
        //
        // AuthController and CustomerPortalController both resolve the customer
        // by findByNptHandle(user.username), so leaving the portal user behind
        // locks them out of OpenWave entirely — a rename that silently costs
        // them their login is worse than no rename at all.
        portalUserService.renameCustomerUser(previous, desired)

        log.info(
            "Handle renamed: '{}' -> '{}' by bank '{}'; '{}' is now permanently retired",
            previous, desired, bankHandle, previous
        )
        return identity
    }

    /**
     * Whether a name can be claimed right now, and why not if it cannot.
     *
     * Exists so a client can ask BEFORE trying. The three answers are genuinely
     * different — taken, retired, or malformed — and a caller that cannot tell
     * them apart shows one useless sentence, which is the shape of bug this
     * whole area has been producing.
     */
    fun handleAvailability(handle: String): HandleAvailability {
        val normalized = handle.trim().lowercase()
        if (!HANDLE_REGEX.matches(normalized)) return HandleAvailability.INVALID
        if (identityRepo.findByNptHandle(normalized) != null) return HandleAvailability.TAKEN
        if (retiredHandleRepo.existsByHandle(normalized)) return HandleAvailability.RETIRED
        return HandleAvailability.AVAILABLE
    }

    private fun ensureCustomerPortalAccess(identity: IdentityEntity, email: String?): CustomerPortalAccessResult? {
        val effectiveEmail = email?.trim()?.takeIf { it.isNotBlank() } ?: identity.email?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val result = portalUserService.ensureCustomerUser(
            username = identity.nptHandle,
            displayName = identity.displayName,
            email = effectiveEmail
        )
        val setupLinkIssued = portalSecurityService.issuePasswordSetupLink(result.user, login = result.user.username)
        return CustomerPortalAccessResult(
            username = result.user.username,
            userCreated = result.created,
            emailConfigured = !result.user.email.isNullOrBlank(),
            passwordSetupLinkIssued = setupLinkIssued,
            nextStep = when {
                setupLinkIssued -> "Customer should use the secure set-password email from OpenWave Identity."
                !result.user.email.isNullOrBlank() -> "Customer portal user exists, but the set-password email was not sent. Use the portal reset flow or operator reset action."
                else -> "Customer portal user exists without email. Add customer email before sending access."
            }
        )
    }

    @Transactional
    fun linkAccount(
        nptHandle: String,
        bankHandle: String,
        iban: String,
        bankCustomerRef: String,
        setAsDefault: Boolean
    ): LinkedAccountEntity {
        bankRepo.findByBankHandle(bankHandle) ?: throw BankNotFoundException(bankHandle)
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)

        if (linkedAccountRepo.existsByIdentityIdAndIban(identity.id, iban))
            throw AccountAlreadyLinkedException(iban)

        val isFirstForBank = !linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, bankHandle)
        val makeDefault   = setAsDefault || isFirstForBank

        // Clear existing default for this bank if we're setting a new one
        if (makeDefault) linkedAccountRepo.clearBankDefaults(identity.id, bankHandle)

        val link = linkedAccountRepo.save(
            LinkedAccountEntity(
                identity        = identity,
                bankHandle      = bankHandle,
                iban            = iban,
                bankCustomerRef = bankCustomerRef,
                isDefault       = makeDefault
            )
        )
        notifyLinkedAccountChange(
            identity = identity,
            bankHandle = bankHandle,
            iban = iban,
            actionLabel = if (isFirstForBank) "New bank linked to your identity" else "New account linked to your identity"
        )

        if (setAsDefault || identity.defaultBankHandle == null) {
            identity.defaultBankHandle = bankHandle
            identity.updatedAt = Instant.now()
            identityRepo.save(identity)
        }
        return link
    }

    @Transactional
    fun updateLinkedAccount(nptHandle: String, iban: String, bankHandle: String, callerBankHandle: String, newIban: String): LinkedAccountEntity {
        if (callerBankHandle != bankHandle) throw ForbiddenException("Bank '$callerBankHandle' does not own the '$bankHandle' account link")
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        val link = linkedAccountRepo.findByIdentityIdAndIban(identity.id, iban)
            ?: throw AccountNotFoundException(iban)
        if (link.bankHandle != bankHandle) throw AccountNotFoundException(iban)
        if (linkedAccountRepo.existsByIdentityIdAndIban(identity.id, newIban))
            throw AccountAlreadyLinkedException(newIban)
        link.iban = newIban
        link.updatedAt = Instant.now()
        return linkedAccountRepo.save(link)
    }

    @Transactional
    fun unlinkAccount(nptHandle: String, iban: String, bankHandle: String, callerBankHandle: String) {
        if (callerBankHandle != bankHandle) throw ForbiddenException("Bank '$callerBankHandle' does not own the '$bankHandle' account link")
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        val link = linkedAccountRepo.findByIdentityIdAndIban(identity.id, iban)
            ?: throw AccountNotFoundException(iban)
        if (link.bankHandle != bankHandle) throw AccountNotFoundException(iban)

        val wasDefault = link.isDefault
        linkedAccountRepo.delete(link)

        // If we removed the default for this bank, promote the next IBAN at same bank
        if (wasDefault) {
            val remaining = linkedAccountRepo.findAllByIdentityIdAndBankHandle(identity.id, bankHandle)
            remaining.firstOrNull()?.let {
                it.isDefault = true
                it.updatedAt = Instant.now()
                linkedAccountRepo.save(it)
            }
        }

        // If no accounts left at all, suspend identity
        val allRemaining = linkedAccountRepo.findAllByIdentityId(identity.id)
        if (allRemaining.isEmpty()) {
            identity.status = IdentityStatus.SUSPENDED
            identity.defaultBankHandle = null
        } else if (identity.defaultBankHandle == bankHandle &&
            linkedAccountRepo.findAllByIdentityIdAndBankHandle(identity.id, bankHandle).isEmpty()) {
            identity.defaultBankHandle = allRemaining.firstOrNull()?.bankHandle
        }
        identity.updatedAt = Instant.now()
        identityRepo.save(identity)
    }

    @Transactional
    fun setDefaultIban(nptHandle: String, iban: String, bankHandle: String, callerBankHandle: String): LinkedAccountEntity {
        if (callerBankHandle != bankHandle) throw ForbiddenException("Bank '$callerBankHandle' does not own the '$bankHandle' account link")
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        val link = linkedAccountRepo.findByIdentityIdAndIban(identity.id, iban)
            ?: throw AccountNotFoundException(iban)
        if (link.bankHandle != bankHandle) throw AccountNotFoundException(iban)
        // Clear existing default for this bank, then set new one
        linkedAccountRepo.clearBankDefaults(identity.id, bankHandle)
        link.isDefault = true
        link.updatedAt = Instant.now()
        return linkedAccountRepo.save(link)
    }

    @Transactional
    fun setDefaultAccountById(accountId: Long, callerBankHandle: String): LinkedAccountEntity {
        val link = linkedAccountRepo.findByIdAndBankHandle(accountId, callerBankHandle)
            ?: throw AccountNotFoundException(accountId.toString())
        linkedAccountRepo.clearBankDefaults(link.identity.id, callerBankHandle)
        link.isDefault = true
        link.updatedAt = Instant.now()
        return linkedAccountRepo.save(link)
    }

    @Transactional
    fun unlinkAccountById(accountId: Long, callerBankHandle: String) {
        val link = linkedAccountRepo.findByIdAndBankHandle(accountId, callerBankHandle)
            ?: throw AccountNotFoundException(accountId.toString())
        unlinkAccount(link.identity.nptHandle, link.iban, link.bankHandle, callerBankHandle)
    }

    @Transactional
    fun setDefaultBank(nptHandle: String, bankHandle: String, callerBankHandle: String): IdentityEntity {
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        if (!linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, bankHandle))
            throw AccountNotFoundException(bankHandle)
        if (!linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, callerBankHandle))
            throw ForbiddenException("Bank '$callerBankHandle' is not linked to this identity")
        identity.defaultBankHandle = bankHandle
        identity.updatedAt = Instant.now()
        return identityRepo.save(identity)
    }

    @Transactional
    fun deleteIdentity(nptHandle: String, callerBankHandle: String) {
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        if (!linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, callerBankHandle))
            throw ForbiddenException("Bank '$callerBankHandle' has no linked account for identity '$nptHandle'")
        identity.status = IdentityStatus.DELETED
        identity.updatedAt = Instant.now()
        identityRepo.save(identity)
    }

    fun getIdentity(nptHandle: String): IdentityEntity =
        identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)

    fun getIdentityOrNull(nptHandle: String): IdentityEntity? =
        identityRepo.findByNptHandle(nptHandle)

    fun getIdentityByPhone(phone: String): IdentityEntity {
        val normalized = normalizePhone(phone) ?: throw IdentityNotFoundException("phone")
        val candidates = linkedSetOf(normalized)
        candidates.add("+$normalized")
        if (normalized.startsWith("218")) candidates.add("0${normalized.removePrefix("218")}")
        if (normalized.startsWith("0")) {
            candidates.add("218${normalized.drop(1)}")
            candidates.add("+218${normalized.drop(1)}")
        }
        return candidates.asSequence()
            .mapNotNull { identityRepo.findByPhone(it) }
            .firstOrNull()
            ?: throw IdentityNotFoundException("phone")
    }

    fun getLinkedAccounts(nptHandle: String, callerBankHandle: String): List<LinkedAccountEntity> {
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        if (!linkedAccountRepo.existsByIdentityIdAndBankHandle(identity.id, callerBankHandle))
            throw ForbiddenException("Bank '$callerBankHandle' is not linked to this identity")
        return linkedAccountRepo.findAllByIdentityId(identity.id)
    }

    fun getLinkedAccountsForBank(nptHandle: String, bankHandle: String, callerBankHandle: String): List<LinkedAccountEntity> {
        if (callerBankHandle != bankHandle) throw ForbiddenException("Bank '$callerBankHandle' cannot view '$bankHandle' accounts")
        val identity = identityRepo.findByNptHandle(nptHandle) ?: throw IdentityNotFoundException(nptHandle)
        return linkedAccountRepo.findAllByIdentityIdAndBankHandle(identity.id, bankHandle)
    }

    fun listAliasesForBank(callerBankHandle: String, activeOnly: Boolean): List<IdentityEntity> {
        val accounts = linkedAccountRepo.findAllByBankHandle(callerBankHandle)
        return accounts
            .map { it.identity }
            .distinctBy { it.id }
            .filter { !activeOnly || it.status == IdentityStatus.ACTIVE }
            .sortedBy { it.nptHandle }
    }

    fun searchAliasesForBank(
        callerBankHandle: String,
        activeOnly: Boolean,
        search: String?,
        page: Int,
        limit: Int,
        maxLimit: Int = 100
    ): Page<IdentityEntity> {
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            limit.coerceIn(1, maxLimit.coerceIn(1, 1_000)),
            Sort.by(Sort.Direction.ASC, "nptHandle")
        )
        return identityRepo.searchAliasesForBank(
            bankHandle = callerBankHandle,
            activeOnly = activeOnly,
            activeStatus = IdentityStatus.ACTIVE,
            needle = search?.trim()?.lowercase()?.takeIf(String::isNotBlank),
            pageable = pageable
        )
    }

    fun countActiveIdentities(): Long =
        identityRepo.countByStatusNot(IdentityStatus.DELETED)

    private fun normalizePhone(phone: String?): String? =
        phone?.filter(Char::isDigit)?.takeIf { it.length in 9..15 }

    private fun notifyLinkedAccountChange(identity: IdentityEntity, bankHandle: String, iban: String, actionLabel: String) {
        val to = identity.email?.trim()?.takeIf { it.isNotBlank() } ?: return
        val bank = bankRepo.findByBankHandle(bankHandle)
        credentialNotificationService.sendLinkedAccountNotice(
            to = to,
            displayName = identity.displayName,
            username = identity.nptHandle,
            bankDisplayName = bank?.displayName ?: bankHandle,
            bankHandle = bankHandle,
            iban = iban,
            actionLabel = actionLabel
        )
    }
}
