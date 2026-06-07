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

@Service
class IdentityService(
    private val identityRepo: IdentityRepository,
    private val linkedAccountRepo: LinkedAccountRepository,
    private val bankRepo: BankRepository,
    private val portalUserService: PortalUserService,
    private val portalSecurityService: PortalSecurityService
) {

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
    ): IdentityEntity {
        if (!HANDLE_REGEX.matches(nptHandle)) throw HandleInvalidFormatException(nptHandle)
        val normalizedPhone = normalizePhone(phone) ?: phone?.trim()?.takeIf { it.isNotBlank() }

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
            if (linkedAccountRepo.existsByIdentityIdAndIban(existing.id, iban)) return existing

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
            if (!email.isNullOrBlank() && existing.email.isNullOrBlank()) {
                existing.email = email.trim()
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
            if (setAsDefault && existing.defaultBankHandle == null) {
                existing.defaultBankHandle = bankHandle
            }
            existing.updatedAt = Instant.now()
            identityRepo.save(existing)
            ensureCustomerPortalAccess(existing, email)
            return existing
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
            email             = email?.trim()?.ifBlank { null }
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
        ensureCustomerPortalAccess(identity, email)
        return identity
    }

    private fun ensureCustomerPortalAccess(identity: IdentityEntity, email: String?) {
        val effectiveEmail = email?.trim()?.takeIf { it.isNotBlank() } ?: identity.email?.trim()?.takeIf { it.isNotBlank() } ?: return
        val result = portalUserService.ensureCustomerUser(
            username = identity.nptHandle,
            displayName = identity.displayName,
            email = effectiveEmail
        )
        portalSecurityService.issuePasswordSetupLink(result.user, login = result.user.username)
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
}
