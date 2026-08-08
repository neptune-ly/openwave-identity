package ly.openwave.identity.service

import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.entity.BankApiCredentialEntity
import ly.openwave.identity.entity.BankCredentialScope
import ly.openwave.identity.exception.BankHandleTakenException
import ly.openwave.identity.exception.BankNotFoundException
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.BankApiCredentialRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*

data class BankRegistrationResult(val bank: BankEntity, val rawApiKey: String, val portalUsername: String, val portalPassword: String)
data class BankApiKeyAuthentication(val bank: BankEntity, val scope: BankCredentialScope?)
data class IssuedBankCredential(val credential: BankApiCredentialEntity, val rawApiKey: String)

@Service
class BankService(
    private val bankRepo: BankRepository,
    private val credentialRepo: BankApiCredentialRepository
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional
    fun registerBank(
        bankHandle: String,
        displayName: String,
        country: String,
        coreUrl: String,
        contactEmail: String
    ): BankRegistrationResult {
        if (bankRepo.existsByBankHandle(bankHandle)) throw BankHandleTakenException(bankHandle)
        val rawKey = generateKey(bankHandle)
        val hash = sha256(rawKey)
        val portalUsername = "${bankHandle}_admin"
        val portalPassword = generatePortalPassword()
        val bank = bankRepo.save(
            BankEntity(
                bankHandle = bankHandle,
                displayName = displayName,
                country = country.uppercase(),
                coreUrl = coreUrl,
                contactEmail = contactEmail,
                apiKeyHash = hash,
                portalUsername = portalUsername,
                portalPasswordHash = passwordEncoder.encode(portalPassword)
            )
        )
        return BankRegistrationResult(bank, rawKey, portalUsername, portalPassword)
    }

    fun getBank(bankHandle: String): BankEntity =
        bankRepo.findByBankHandle(bankHandle) ?: throw BankNotFoundException(bankHandle)

    fun listBanks(country: String?, activeOnly: Boolean): List<BankEntity> =
        if (country != null) bankRepo.findAllByCountryAndActiveTrue(country.uppercase())
        else if (activeOnly) bankRepo.findAllByActiveTrue()
        else bankRepo.findAll()

    @Transactional
    fun updateBank(
        bankHandle: String,
        coreUrl: String?,
        displayName: String?,
        contactEmail: String?,
        active: Boolean?,
        logoUrl: String? = null,
        brandColor: String? = null,
        supportEmail: String? = null,
        website: String? = null
    ): BankEntity {
        val bank = getBank(bankHandle)
        coreUrl?.let { bank.coreUrl = it }
        displayName?.let { bank.displayName = it }
        contactEmail?.let { bank.contactEmail = it }
        active?.let { bank.active = it }
        logoUrl?.let { bank.logoUrl = it.takeIf { value -> value.isNotBlank() } }
        brandColor?.let { bank.brandColor = normalizeColor(it) }
        supportEmail?.let { bank.supportEmail = it.takeIf { value -> value.isNotBlank() } }
        website?.let { bank.website = it.takeIf { value -> value.isNotBlank() } }
        bank.updatedAt = Instant.now()
        return bankRepo.save(bank)
    }

    /** Existing registered_banks.api_key_hash values remain full-bank credentials. */
    fun resolveByApiKey(apiKey: String): BankEntity? = resolveApiKeyAuthentication(apiKey)?.bank

    fun resolveApiKeyAuthentication(apiKey: String): BankApiKeyAuthentication? {
        val hash = sha256(apiKey)
        bankRepo.findByApiKeyHash(hash)?.let { return BankApiKeyAuthentication(it, null) }
        val credential = credentialRepo.findByApiKeyHashAndActiveTrue(hash) ?: return null
        if (credential.revokedAt != null || !credential.bank.active) return null
        return BankApiKeyAuthentication(credential.bank, credential.scope)
    }

    fun listCredentials(bankHandle: String): List<BankApiCredentialEntity> {
        getBank(bankHandle)
        return credentialRepo.findAllByBank_BankHandleOrderByCreatedAtDesc(bankHandle)
    }

    @Transactional
    fun issueCredential(
        bankHandle: String,
        scope: BankCredentialScope,
        label: String,
        createdBy: String?
    ): IssuedBankCredential {
        val bank = getBank(bankHandle)
        require(bank.active) { "Cannot issue a credential for an inactive bank." }
        val normalizedLabel = label.trim()
        require(normalizedLabel.matches(Regex("^[A-Za-z0-9][A-Za-z0-9 .:_-]{1,119}$"))) {
            "Credential label must be 2-120 safe characters."
        }
        // SHA-256 collisions are fantastically unlikely, but do not let a collision
        // silently turn an issued key into another bank's credential.
        repeat(3) {
            val rawKey = generateKey(bank.bankHandle)
            val hash = sha256(rawKey)
            if (bankRepo.findByApiKeyHash(hash) != null || credentialRepo.existsByApiKeyHash(hash)) return@repeat
            val credential = credentialRepo.saveAndFlush(
                BankApiCredentialEntity(
                    bank = bank,
                    apiKeyHash = hash,
                    scope = scope,
                    label = normalizedLabel,
                    createdBy = createdBy?.take(160)
                )
            )
            return IssuedBankCredential(credential, rawKey)
        }
        throw IllegalStateException("Could not issue a unique bank credential.")
    }

    @Transactional
    fun revokeCredential(bankHandle: String, credentialId: Long): BankApiCredentialEntity {
        val credential = credentialRepo.findByIdAndBank_BankHandle(credentialId, bankHandle)
            ?: throw BankNotFoundException(bankHandle)
        if (credential.active) {
            credential.active = false
            credential.revokedAt = Instant.now()
            credentialRepo.save(credential)
        }
        return credential
    }

    fun resolveByPortalLogin(username: String, password: String): BankEntity? {
        val bank = bankRepo.findByPortalUsername(username) ?: return null
        val hash = bank.portalPasswordHash ?: return null
        return if (bank.active && passwordEncoder.matches(password, hash)) bank else null
    }

    fun count(): Long = bankRepo.count()

    private fun generateKey(bankHandle: String): String {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return "owbk_${bankHandle}_${Base64.getUrlEncoder().withoutPadding().encodeToString(random)}"
    }

    private fun generatePortalPassword(): String {
        val random = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeColor(value: String?): String? =
        value?.trim()?.takeIf { it.matches(Regex("^#?[0-9A-Fa-f]{6}$")) }?.let {
            if (it.startsWith("#")) it.uppercase() else "#${it.uppercase()}"
        }
}
