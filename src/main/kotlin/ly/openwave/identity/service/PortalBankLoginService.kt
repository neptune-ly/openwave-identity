package ly.openwave.identity.service

import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.entity.BankLoginChallengeStatus
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.PortalBankLoginChallengeEntity
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.exception.LoginApprovalInvalidFilterException
import ly.openwave.identity.exception.LoginApprovalNotFoundException
import ly.openwave.identity.exception.LoginApprovalNotPermittedException
import ly.openwave.identity.exception.LoginApprovalTerminalException
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalBankLoginChallengeRepository
import ly.openwave.identity.security.PortalTokenService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

data class PortalBankLoginChallengeResult(
    val challengeId: String,
    val statusToken: String,
    val expiresIn: Long,
    val identifierType: String,
    val identifierHint: String,
    val defaultBankHandle: String?,
    val banks: List<PortalBankLoginBankSummary>
)

data class PortalBankLoginBankSummary(
    val bankHandle: String,
    val alias: String,
    val isDefault: Boolean
)

data class PortalBankLoginChallengeStatusResult(
    val challengeId: String,
    val status: String,
    val expiresIn: Long,
    val bankHandle: String?,
    val session: Map<String, Any?>?
)

data class PortalBankLoginQueueItem(
    val challengeId: String,
    val status: String,
    val identifierType: String,
    val identifierHint: String,
    val requestedAlias: String,
    val defaultBankHandle: String?,
    val bankCustomerRef: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val actionedAt: Instant?
)

data class PortalCustomerLoginApprovalItem(
    val challengeId: String,
    val status: String,
    val identifierType: String,
    val identifierHint: String,
    val requestedAlias: String,
    val defaultBankHandle: String?,
    val approvedBankHandle: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val actionedAt: Instant?,
    val bankOptions: List<Map<String, Any?>>
)

@Service
class PortalBankLoginService(
    private val challengeRepository: PortalBankLoginChallengeRepository,
    private val linkedAccountRepository: LinkedAccountRepository,
    private val portalUserService: PortalUserService,
    private val portalTokenService: PortalTokenService
) {
    private val random = SecureRandom()
    private val challengeTtlSeconds = 300L
    private val sessionTtlSeconds = 28_800L

    @Transactional
    fun createChallenge(
        user: PortalUserEntity,
        identity: IdentityEntity,
        identifierType: String,
        rawIdentifier: String,
        request: HttpServletRequest?
    ): PortalBankLoginChallengeResult {
        require(user.role == PortalRole.CUSTOMER) { "Bank login challenge is only supported for customer users." }
        val linkedAccounts = identity.linkedAccounts.sortedWith(compareByDescending<ly.openwave.identity.entity.LinkedAccountEntity> { it.isDefault }.thenBy { it.bankHandle })
        require(linkedAccounts.isNotEmpty()) { "No linked bank accounts are available for this identity." }

        val challengeId = randomId(48)
        val statusToken = randomId(48)
        val expiresAt = Instant.now().plusSeconds(challengeTtlSeconds)
        val ipAddress = request?.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request?.remoteAddr?.trim()?.takeIf { it.isNotBlank() }

        challengeRepository.save(
            PortalBankLoginChallengeEntity(
                id = challengeId,
                user = user,
                identity = identity,
                identifierType = identifierType,
                identifierHint = maskIdentifier(identifierType, rawIdentifier),
                statusTokenHash = hashToken(statusToken),
                expiresAt = expiresAt,
                ipAddress = ipAddress
            )
        )

        return PortalBankLoginChallengeResult(
            challengeId = challengeId,
            statusToken = statusToken,
            expiresIn = challengeTtlSeconds,
            identifierType = identifierType,
            identifierHint = maskIdentifier(identifierType, rawIdentifier),
            defaultBankHandle = identity.defaultBankHandle,
            banks = linkedAccounts
                .distinctBy { it.bankHandle }
                .map {
                    PortalBankLoginBankSummary(
                        bankHandle = it.bankHandle,
                        alias = "${identity.nptHandle}@${it.bankHandle}",
                        isDefault = it.bankHandle == identity.defaultBankHandle
                    )
                }
        )
    }

    @Transactional(readOnly = true)
    fun getStatus(challengeId: String, statusToken: String?): PortalBankLoginChallengeStatusResult {
        val challenge = challengeRepository.findById(challengeId)
            .orElseThrow { LoginApprovalNotFoundException("Bank login challenge was not found.") }
        if (!challenge.matchesStatusToken(statusToken)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Login approval status token is invalid.")
        }
        return buildStatusResult(challenge)
    }

    private fun buildStatusResult(challenge: PortalBankLoginChallengeEntity): PortalBankLoginChallengeStatusResult {
        val now = Instant.now()
        val effectiveStatus = challenge.effectiveStatus(now)
        val expiresIn = (challenge.expiresAt.epochSecond - now.epochSecond).coerceAtLeast(0)
        val sessionExpiresIn = challenge.portalSessionExpiresAt
            ?.let { (it.epochSecond - now.epochSecond).coerceAtLeast(0) }
            ?: 0
        val sessionPayload = if (
            effectiveStatus == "APPROVED" &&
            !challenge.portalSessionToken.isNullOrBlank() &&
            sessionExpiresIn > 0
        ) {
            mapOf(
                "role" to "CUSTOMER",
                "username" to challenge.user.username,
                "bankHandle" to null,
                "portalRole" to challenge.user.role.name,
                "sessionToken" to challenge.portalSessionToken,
                "expiresIn" to sessionExpiresIn
            )
        } else {
            null
        }
        return PortalBankLoginChallengeStatusResult(
            challengeId = challenge.id,
            status = effectiveStatus,
            expiresIn = expiresIn,
            bankHandle = challenge.approvedBankHandle,
            session = sessionPayload
        )
    }

    @Transactional(readOnly = true)
    fun listPending(bankHandle: String, bankCustomerRef: String): List<Map<String, Any?>> =
        challengeRepository.findPendingForBankCustomer(
            bankHandle = bankHandle,
            bankCustomerRef = bankCustomerRef,
            status = BankLoginChallengeStatus.PENDING,
            now = Instant.now()
        ).map { toQueueItem(it, bankHandle) }.map(::queueItemMap)

    @Transactional(readOnly = true)
    fun getForBank(bankHandle: String, challengeId: String): Map<String, Any?> {
        val challenge = challengeRepository.findForBankByChallengeId(challengeId, bankHandle)
            .orElseThrow { LoginApprovalNotFoundException("Bank login challenge was not found for this bank.") }
        return queueItemMap(toQueueItem(challenge, bankHandle))
    }

    @Transactional(readOnly = true)
    fun listForBank(bankHandle: String, status: String?, search: String?, limit: Int): Map<String, Any?> {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.also {
            if (it !in setOf("PENDING", "APPROVED", "REJECTED", "EXPIRED")) {
                throw LoginApprovalInvalidFilterException(status)
            }
        }
        val repositoryStatus = normalizedStatus
            ?.takeUnless { it == "EXPIRED" }
            ?.let(BankLoginChallengeStatus::valueOf)
        val fetchedRows = challengeRepository.findForBank(
            bankHandle = bankHandle,
            status = repositoryStatus,
            needle = search?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        )
        val rows = normalizedStatus
            ?.let { expected -> fetchedRows.filter { it.effectiveStatus() == expected } }
            ?: fetchedRows
        val capped = rows.take(limit.coerceIn(1, 100))
        val items = capped.map { toQueueItem(it, bankHandle) }
        return mapOf(
            "items" to items.map(::queueItemMap),
            "summary" to mapOf(
                "total" to rows.size,
                "pending" to rows.count { it.effectiveStatus() == "PENDING" },
                "approved" to rows.count { it.effectiveStatus() == "APPROVED" },
                "rejected" to rows.count { it.effectiveStatus() == "REJECTED" },
                "expired" to rows.count { it.effectiveStatus() == "EXPIRED" }
            ),
            "limit" to limit.coerceIn(1, 100)
        )
    }

    @Transactional(readOnly = true)
    fun listForCustomer(identity: IdentityEntity, limit: Int, status: String?): Map<String, Any?> {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.also {
            if (it !in setOf("PENDING", "APPROVED", "REJECTED", "EXPIRED")) {
                throw LoginApprovalInvalidFilterException(status)
            }
        }
        val cappedLimit = limit.coerceIn(1, 25)
        val pageable = PageRequest.of(0, cappedLimit)
        val now = Instant.now()
        val page = when (normalizedStatus) {
            "PENDING" -> challengeRepository.findActivePendingForIdentity(
                identityId = identity.id,
                pendingStatus = BankLoginChallengeStatus.PENDING,
                now = now,
                pageable = pageable
            )
            "EXPIRED" -> challengeRepository.findExpiredForIdentity(
                identityId = identity.id,
                pendingStatus = BankLoginChallengeStatus.PENDING,
                now = now,
                pageable = pageable
            )
            else -> challengeRepository.findForIdentity(
                identityId = identity.id,
                status = normalizedStatus?.let(BankLoginChallengeStatus::valueOf),
                pageable = pageable
            )
        }
        val items = page.content.map { challenge ->
            PortalCustomerLoginApprovalItem(
                challengeId = challenge.id,
                status = challenge.effectiveStatus(),
                identifierType = challenge.identifierType,
                identifierHint = challenge.identifierHint,
                requestedAlias = challenge.identity.nptHandle,
                defaultBankHandle = challenge.identity.defaultBankHandle,
                approvedBankHandle = challenge.approvedBankHandle,
                createdAt = challenge.createdAt,
                expiresAt = challenge.expiresAt,
                actionedAt = challenge.actionedAt,
                bankOptions = challenge.identity.linkedAccounts
                    .sortedWith(compareByDescending<ly.openwave.identity.entity.LinkedAccountEntity> { it.isDefault }.thenBy { it.bankHandle })
                    .distinctBy { it.bankHandle }
                    .map {
                        mapOf(
                            "bank_handle" to it.bankHandle,
                            "alias" to "${challenge.identity.nptHandle}@${it.bankHandle}",
                            "is_default" to (it.bankHandle == challenge.identity.defaultBankHandle),
                            "approved" to (challenge.status == BankLoginChallengeStatus.APPROVED && challenge.approvedBankHandle == it.bankHandle)
                        )
                    }
            )
        }
        return mapOf(
            "items" to items.map(::customerQueueItemMap),
            "summary" to mapOf(
                "total" to page.totalElements,
                "pending" to items.count { it.status == "PENDING" },
                "approved" to items.count { it.status == "APPROVED" },
                "rejected" to items.count { it.status == "REJECTED" },
                "expired" to items.count { it.status == "EXPIRED" }
            ),
            "limit" to cappedLimit
        )
    }

    @Transactional(readOnly = true)
    fun getForCustomer(identity: IdentityEntity, challengeId: String): Map<String, Any?> {
        val challenge = challengeRepository.findForIdentityByChallengeId(challengeId, identity.id)
            .orElseThrow { LoginApprovalNotFoundException("Login approval was not found for this customer identity.") }
        val item = PortalCustomerLoginApprovalItem(
            challengeId = challenge.id,
            status = challenge.effectiveStatus(),
            identifierType = challenge.identifierType,
            identifierHint = challenge.identifierHint,
            requestedAlias = challenge.identity.nptHandle,
            defaultBankHandle = challenge.identity.defaultBankHandle,
            approvedBankHandle = challenge.approvedBankHandle,
            createdAt = challenge.createdAt,
            expiresAt = challenge.expiresAt,
            actionedAt = challenge.actionedAt,
            bankOptions = challenge.identity.linkedAccounts
                .sortedWith(compareByDescending<ly.openwave.identity.entity.LinkedAccountEntity> { it.isDefault }.thenBy { it.bankHandle })
                .distinctBy { it.bankHandle }
                .map {
                    mapOf(
                        "bank_handle" to it.bankHandle,
                        "alias" to "${challenge.identity.nptHandle}@${it.bankHandle}",
                        "is_default" to (it.bankHandle == challenge.identity.defaultBankHandle),
                        "approved" to (challenge.status == BankLoginChallengeStatus.APPROVED && challenge.approvedBankHandle == it.bankHandle)
                    )
                }
        )
        return customerQueueItemMap(item)
    }

    @Transactional
    fun approve(challengeId: String, bankHandle: String, bankCustomerRef: String): PortalBankLoginChallengeStatusResult {
        val challenge = challengeRepository.findById(challengeId)
            .orElseThrow { LoginApprovalNotFoundException("Bank login challenge was not found.") }
        if (!challenge.isPending()) {
            throw LoginApprovalTerminalException(challenge.effectiveStatus())
        }
        val allowed = linkedAccountRepository.existsByIdentityIdAndBankHandleAndBankCustomerRef(
            challenge.identity.id,
            bankHandle,
            bankCustomerRef
        )
        if (!allowed) {
            throw LoginApprovalNotPermittedException(
                "This bank customer cannot approve the requested OpenWave Identity login."
            )
        }

        val sessionToken = portalTokenService.issue(
            challenge.user.username,
            "CUSTOMER",
            null,
            challenge.user.role.name
        )
        challenge.status = BankLoginChallengeStatus.APPROVED
        challenge.approvedBankHandle = bankHandle
        challenge.portalSessionToken = sessionToken
        challenge.portalSessionExpiresAt = Instant.now().plusSeconds(sessionTtlSeconds)
        challenge.actionedAt = Instant.now()
        portalUserService.recordSuccessfulLogin(challenge.user)
        challengeRepository.save(challenge)
        return buildStatusResult(challenge)
    }

    @Transactional
    fun reject(challengeId: String, bankHandle: String, bankCustomerRef: String): PortalBankLoginChallengeStatusResult {
        val challenge = challengeRepository.findById(challengeId)
            .orElseThrow { LoginApprovalNotFoundException("Bank login challenge was not found.") }
        if (!challenge.isPending()) {
            throw LoginApprovalTerminalException(challenge.effectiveStatus())
        }
        val allowed = linkedAccountRepository.existsByIdentityIdAndBankHandleAndBankCustomerRef(
            challenge.identity.id,
            bankHandle,
            bankCustomerRef
        )
        if (!allowed) {
            throw LoginApprovalNotPermittedException(
                "This bank customer cannot reject the requested OpenWave Identity login."
            )
        }

        challenge.status = BankLoginChallengeStatus.REJECTED
        challenge.approvedBankHandle = bankHandle
        challenge.actionedAt = Instant.now()
        challengeRepository.save(challenge)
        return buildStatusResult(challenge)
    }

    private fun maskIdentifier(type: String, rawIdentifier: String): String {
        val digits = rawIdentifier.filter(Char::isDigit)
        return when (type) {
            "PHONE" -> if (digits.length <= 4) "***" else "***${digits.takeLast(4)}"
            "NATIONAL_ID" -> if (digits.length <= 4) "***" else "********${digits.takeLast(4)}"
            else -> rawIdentifier.take(2) + "***"
        }
    }

    private fun randomId(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun toQueueItem(challenge: PortalBankLoginChallengeEntity, bankHandle: String): PortalBankLoginQueueItem {
        val matchingAccount = challenge.identity.linkedAccounts.firstOrNull { it.bankHandle == bankHandle }
            ?: challenge.identity.linkedAccounts.firstOrNull()
        return PortalBankLoginQueueItem(
            challengeId = challenge.id,
            status = challenge.effectiveStatus(),
            identifierType = challenge.identifierType,
            identifierHint = challenge.identifierHint,
            requestedAlias = challenge.identity.nptHandle,
            defaultBankHandle = challenge.identity.defaultBankHandle,
            bankCustomerRef = matchingAccount?.bankCustomerRef,
            createdAt = challenge.createdAt,
            expiresAt = challenge.expiresAt,
            actionedAt = challenge.actionedAt
        )
    }

    private fun queueItemMap(item: PortalBankLoginQueueItem): Map<String, Any?> =
        mapOf(
            "challenge_id" to item.challengeId,
            "status" to item.status,
            "identifier_type" to item.identifierType,
            "identifier_hint" to item.identifierHint,
            "requested_alias" to item.requestedAlias,
            "default_bank_handle" to item.defaultBankHandle,
            "bank_customer_ref" to item.bankCustomerRef,
            "created_at" to item.createdAt,
            "expires_at" to item.expiresAt,
            "actioned_at" to item.actionedAt
        )

    private fun customerQueueItemMap(item: PortalCustomerLoginApprovalItem): Map<String, Any?> =
        mapOf(
            "challenge_id" to item.challengeId,
            "status" to item.status,
            "identifier_type" to item.identifierType,
            "identifier_hint" to item.identifierHint,
            "requested_alias" to item.requestedAlias,
            "default_bank_handle" to item.defaultBankHandle,
            "approved_bank_handle" to item.approvedBankHandle,
            "created_at" to item.createdAt,
            "expires_at" to item.expiresAt,
            "actioned_at" to item.actionedAt,
            "bank_options" to item.bankOptions
        )
}
