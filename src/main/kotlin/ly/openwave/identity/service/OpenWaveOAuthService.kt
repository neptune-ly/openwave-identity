package ly.openwave.identity.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ly.openwave.identity.config.OAuthProperties
import ly.openwave.identity.entity.*
import ly.openwave.identity.repository.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class OAuthTokenIssueResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val scope: String,
    val tokenType: String = "Bearer"
)

@Service
class OpenWaveOAuthService(
    private val props: OAuthProperties,
    private val clients: OAuthClientRepository,
    private val tokens: OAuthTokenRepository,
    private val settings: OAuthSettingRepository,
    private val grants: OAuthUserGrantRepository,
    private val audit: PortalAuditService
) {
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()
    private val json = jacksonObjectMapper()

    private val knownSettings = listOf(
        "oauth.global" to false,
        "mcp.global" to false,
        "mcp.mutations" to false,
        "environment.sandbox" to true,
        "environment.live" to false,
        "owner.NEPTUNE" to true,
        "owner.MERCHANT" to false,
        "owner.BANK" to false,
        "owner.CUSTOMER" to false
    )

    fun discovery(baseUrl: String): Map<String, Any?> = mapOf(
        "issuer" to props.issuer.trimEnd('/'),
        "authorization_endpoint" to "$baseUrl/oauth/authorize",
        "token_endpoint" to "$baseUrl/oauth/token",
        "revocation_endpoint" to "$baseUrl/oauth/revoke",
        "introspection_endpoint" to "$baseUrl/oauth/introspect",
        "jwks_uri" to "$baseUrl/oauth/jwks",
        "response_types_supported" to emptyList<String>(),
        "grant_types_supported" to listOf("client_credentials", "refresh_token"),
        "token_endpoint_auth_methods_supported" to listOf("client_secret_basic", "client_secret_post"),
        "code_challenge_methods_supported" to listOf("S256"),
        "scopes_supported" to supportedScopes()
    )

    fun protectedResource(baseUrl: String): Map<String, Any?> = mapOf(
        "resource" to baseUrl,
        "authorization_servers" to listOf(props.issuer.trimEnd('/')),
        "scopes_supported" to supportedScopes(),
        "bearer_methods_supported" to listOf("header")
    )

    fun jwks(): Map<String, Any?> = mapOf("keys" to emptyList<Map<String, Any?>>())

    @Transactional
    fun createClient(req: CreateOAuthClientRequest, authentication: Authentication?): Map<String, Any?> {
        ensureSettings()
        val clientId = req.clientId?.takeIf { it.isNotBlank() } ?: "owc_${randomToken(18)}"
        if (clients.existsByClientId(clientId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "OAuth client already exists")
        }
        val rawSecret = if (req.clientType != OAuthClientType.PUBLIC) "ows_${randomToken(32)}" else null
        val entity = clients.save(
            OAuthClientEntity(
                clientId = clientId,
                clientSecretHash = rawSecret?.let { encoder.encode(it) },
                displayName = req.displayName,
                clientType = req.clientType,
                ownerType = req.ownerType,
                ownerId = req.ownerId,
                ownerHandle = req.ownerHandle,
                redirectUris = json.writeValueAsString(validateRedirectUris(req.redirectUris)),
                allowedScopes = json.writeValueAsString(req.allowedScopes.distinct().filter { it in supportedScopes() }),
                allowedEnvironments = req.allowedEnvironments.distinct().joinToString(","),
                active = req.active,
                mcpEnabled = req.mcpEnabled,
                liveEnabled = req.liveEnabled
            )
        )
        audit.record(authentication, "OAUTH_CLIENT_CREATED", "OAUTH_CLIENT", entity.clientId, mapOf(
            "owner_type" to entity.ownerType.name,
            "mcp_enabled" to entity.mcpEnabled,
            "live_enabled" to entity.liveEnabled
        ))
        return clientResponse(entity, rawSecret)
    }

    fun listClients(): List<Map<String, Any?>> = clients.findAll().sortedBy { it.clientId }.map { clientResponse(it, null) }

    @Transactional
    fun updateClient(clientId: String, req: UpdateOAuthClientRequest, authentication: Authentication?): Map<String, Any?> {
        val client = clients.findByClientId(clientId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth client not found")
        req.displayName?.let { client.displayName = it }
        req.redirectUris?.let { client.redirectUris = json.writeValueAsString(validateRedirectUris(it)) }
        req.allowedScopes?.let { client.allowedScopes = json.writeValueAsString(it.distinct().filter { scope -> scope in supportedScopes() }) }
        req.allowedEnvironments?.let { client.allowedEnvironments = it.distinct().joinToString(",") }
        req.active?.let {
            client.active = it
            if (!it) client.revokedAt = Instant.now()
        }
        req.mcpEnabled?.let { client.mcpEnabled = it }
        req.liveEnabled?.let { client.liveEnabled = it }
        client.updatedAt = Instant.now()
        audit.record(authentication, "OAUTH_CLIENT_UPDATED", "OAUTH_CLIENT", clientId, mapOf(
            "active" to client.active,
            "mcp_enabled" to client.mcpEnabled,
            "live_enabled" to client.liveEnabled
        ))
        return clientResponse(clients.save(client), null)
    }

    @Transactional
    fun rotateClientSecret(clientId: String, authentication: Authentication?): Map<String, Any?> {
        val client = clients.findByClientId(clientId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth client not found")
        if (client.clientType == OAuthClientType.PUBLIC) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Public clients do not have secrets")
        val rawSecret = "ows_${randomToken(32)}"
        client.clientSecretHash = encoder.encode(rawSecret)
        client.secretRotatedAt = Instant.now()
        client.updatedAt = Instant.now()
        audit.record(authentication, "OAUTH_CLIENT_SECRET_ROTATED", "OAUTH_CLIENT", clientId)
        return clientResponse(clients.save(client), rawSecret)
    }

    @Transactional
    fun revokeClientTokens(clientId: String, authentication: Authentication?): Map<String, Any?> {
        val now = Instant.now()
        val rows = tokens.findAllByClientId(clientId)
        rows.filter { it.revokedAt == null }.forEach {
            it.revokedAt = now
            it.revokeReason = "client_revoked"
        }
        tokens.saveAll(rows)
        audit.record(authentication, "OAUTH_CLIENT_TOKENS_REVOKED", "OAUTH_CLIENT", clientId, mapOf("count" to rows.size))
        return mapOf("client_id" to clientId, "revoked_tokens" to rows.size)
    }

    @Transactional
    fun revokeUserGrants(subject: String, authentication: Authentication?): Map<String, Any?> {
        val now = Instant.now()
        val tokenRows = tokens.findAllBySubject(subject)
        tokenRows.filter { it.revokedAt == null }.forEach {
            it.revokedAt = now
            it.revokeReason = "user_grants_revoked"
        }
        val grantRows = grants.findAllBySubject(subject)
        grantRows.filter { it.active }.forEach {
            it.active = false
            it.revokedAt = now
            it.revokedBy = authentication?.name ?: "system"
        }
        tokens.saveAll(tokenRows)
        grants.saveAll(grantRows)
        audit.record(authentication, "OAUTH_USER_GRANTS_REVOKED", "OAUTH_USER", subject, mapOf("tokens" to tokenRows.size, "grants" to grantRows.size))
        return mapOf("subject" to subject, "revoked_tokens" to tokenRows.size, "revoked_grants" to grantRows.size)
    }

    @Transactional
    fun issueClientCredentials(
        clientId: String,
        clientSecret: String?,
        scopeText: String?,
        audience: String?,
        environmentText: String?
    ): OAuthTokenIssueResult {
        ensureSettings()
        requireSetting("oauth.global")
        val client = clients.findByClientId(clientId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client")
        validateClientSecret(client, clientSecret)
        val environment = parseEnvironment(environmentText)
        requireSetting("environment.${environment.name.lowercase()}")
        requireSetting("owner.${client.ownerType.name}")
        if (environment == OAuthEnvironment.LIVE && !client.liveEnabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Live OAuth access is disabled for this client")
        }
        // RFC 6749 §3.3: an absent/empty `scope` must NOT be treated as "grant everything".
        // Require the client to request scopes explicitly; never widen to the full allowed set.
        val requested = parseScopeText(scopeText)
        if (requested.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scope is required; empty scope grants no access")
        }
        val allowed = parseJsonList(client.allowedScopes).toSet()
        val granted = requested.filter { it in allowed }.toSet()
        if (granted.isEmpty()) throw ResponseStatusException(HttpStatus.FORBIDDEN, "No requested scopes are allowed for this client")
        if (granted.any { it.startsWith("openwave:mcp") } && !client.mcpEnabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "MCP access is disabled for this client")
        }
        val accessToken = "owat_${randomToken(40)}"
        val refreshToken = "owrt_${randomToken(40)}"
        val now = Instant.now()
        tokens.save(
            OAuthTokenEntity(
                tokenHash = sha256(accessToken),
                refreshTokenHash = sha256(refreshToken),
                clientId = client.clientId,
                subject = client.clientId,
                audience = audience?.takeIf { it.isNotBlank() } ?: "astro",
                scopes = granted.joinToString(" "),
                ownerType = client.ownerType,
                ownerId = client.ownerId,
                ownerHandle = client.ownerHandle,
                environment = environment,
                grantType = "client_credentials",
                issuedAt = now,
                expiresAt = now.plusSeconds(props.accessTokenTtlSeconds),
                refreshExpiresAt = now.plusSeconds(props.refreshTokenTtlSeconds)
            )
        )
        return OAuthTokenIssueResult(accessToken, refreshToken, props.accessTokenTtlSeconds, granted.joinToString(" "))
    }

    // noRollbackFor: when refresh-token reuse is detected we revoke the active token family and
    // THEN throw 401. Without this, Spring would roll back the family-revocation along with the
    // exception, leaving the leaked descendant usable. The legitimate failure throws below write
    // nothing, so suppressing rollback for them is harmless.
    @Transactional(noRollbackFor = [ResponseStatusException::class])
    fun refresh(refreshToken: String, scopeText: String?): OAuthTokenIssueResult {
        ensureSettings()
        requireSetting("oauth.global")
        val row = tokens.findByRefreshTokenHash(sha256(refreshToken))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        // OAuth 2.0 Security BCP (RFC 9700 §4.14.2) / RFC 6819 §5.2.2.3 refresh-token reuse
        // detection: each refresh token is single-use and revoked the moment it is rotated
        // (revokeReason = "refresh_rotated"). Presenting an already-rotated refresh token means
        // either the legitimate client retried after a lost response OR a leaked token is being
        // replayed in parallel with the legitimate descendant. We cannot tell the two apart, so
        // we fail closed AND revoke the whole active token family for that client — invalidating
        // the attacker's stolen descendant as well. The happy path (a still-active refresh token)
        // is unaffected.
        if (row.revokedAt != null) {
            if (row.revokeReason == "refresh_rotated") {
                revokeActiveTokenFamily(row.clientId, "refresh_reuse_detected")
            }
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired or revoked")
        }
        if (Instant.now().isAfter(row.refreshExpiresAt ?: row.expiresAt)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired or revoked")
        }
        val client = clients.findByClientId(row.clientId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client")
        requireSetting("environment.${row.environment.name.lowercase()}")
        requireSetting("owner.${row.ownerType.name}")
        val requested = parseScopeText(scopeText).ifEmpty { row.scopes.split(" ").filter { it.isNotBlank() }.toSet() }
        val granted = requested.filter { it in row.scopes.split(" ").toSet() }.toSet()
        row.revokedAt = Instant.now()
        row.revokeReason = "refresh_rotated"
        tokens.save(row)
        return issueRotatedToken(client, row, granted)
    }

    /**
     * Revokes every still-active token for a client. Used as the fail-closed response to refresh
     * token reuse: when a rotated (single-use) refresh token is replayed, the entire active token
     * family is invalidated so a leaked descendant cannot continue to be used.
     */
    private fun revokeActiveTokenFamily(clientId: String, reason: String) {
        val now = Instant.now()
        val active = tokens.findAllByClientIdAndRevokedAtIsNull(clientId)
        if (active.isEmpty()) return
        active.forEach {
            it.revokedAt = now
            it.revokeReason = reason
        }
        tokens.saveAll(active)
    }

    private fun issueRotatedToken(client: OAuthClientEntity, source: OAuthTokenEntity, scopes: Set<String>): OAuthTokenIssueResult {
        val accessToken = "owat_${randomToken(40)}"
        val refreshToken = "owrt_${randomToken(40)}"
        val now = Instant.now()
        tokens.save(
            OAuthTokenEntity(
                tokenHash = sha256(accessToken),
                refreshTokenHash = sha256(refreshToken),
                clientId = client.clientId,
                subject = source.subject,
                audience = source.audience,
                scopes = scopes.joinToString(" "),
                ownerType = source.ownerType,
                ownerId = source.ownerId,
                ownerHandle = source.ownerHandle,
                environment = source.environment,
                grantType = "refresh_token",
                issuedAt = now,
                expiresAt = now.plusSeconds(props.accessTokenTtlSeconds),
                refreshExpiresAt = now.plusSeconds(props.refreshTokenTtlSeconds)
            )
        )
        return OAuthTokenIssueResult(accessToken, refreshToken, props.accessTokenTtlSeconds, scopes.joinToString(" "))
    }

    /**
     * Authenticates the caller as a registered OAuth client (RFC 7662/7009 endpoint protection).
     * Confidential clients must present a valid secret; public clients authenticate by id alone.
     * Returns the authenticated client so callers (e.g. revoke) can scope actions to its tokens.
     */
    fun authenticateClient(clientId: String?, clientSecret: String?): OAuthClientEntity {
        if (clientId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Client authentication required")
        }
        val client = clients.findByClientId(clientId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials")
        validateClientSecret(client, clientSecret)
        return client
    }

    @Transactional
    fun revoke(token: String, client: OAuthClientEntity? = null): Map<String, Any?> {
        val hash = sha256(token)
        val row = tokens.findByTokenHash(hash) ?: tokens.findByRefreshTokenHash(hash) ?: return mapOf("revoked" to true)
        // RFC 7009: a client may only revoke tokens it owns. When an authenticated client is
        // supplied, silently no-op (return success without revoking) for tokens it does not own,
        // so the endpoint does not become a token-existence oracle for other clients' tokens.
        if (client != null && row.clientId != client.clientId) {
            return mapOf("revoked" to true)
        }
        row.revokedAt = Instant.now()
        row.revokeReason = "token_revoked"
        tokens.save(row)
        return mapOf("revoked" to true)
    }

    fun introspect(token: String, requiredAudience: String? = null): Map<String, Any?> {
        ensureSettings()
        val row = tokens.findByTokenHash(sha256(token)) ?: return mapOf("active" to false)
        val client = clients.findByClientId(row.clientId)
        val now = Instant.now()
        val active = row.revokedAt == null &&
            now.isBefore(row.expiresAt) &&
            client?.active == true &&
            settingEnabled("oauth.global") &&
            settingEnabled("environment.${row.environment.name.lowercase()}") &&
            settingEnabled("owner.${row.ownerType.name}") &&
            (requiredAudience.isNullOrBlank() || row.audience == requiredAudience || row.audience == "openwave")
        return if (!active) {
            mapOf("active" to false)
        } else {
            mapOf(
                "active" to true,
                "iss" to props.issuer.trimEnd('/'),
                "sub" to row.subject,
                "aud" to row.audience,
                "scope" to row.scopes,
                "client_id" to row.clientId,
                "owner_type" to row.ownerType.name,
                "owner_id" to row.ownerId,
                "owner_handle" to row.ownerHandle,
                "environment" to row.environment.name,
                "exp" to row.expiresAt.epochSecond,
                "iat" to row.issuedAt.epochSecond,
                "mcp_enabled" to (client?.mcpEnabled == true && settingEnabled("mcp.global")),
                "mcp_mutations_enabled" to settingEnabled("mcp.mutations")
            )
        }
    }

    @Transactional
    fun setSwitch(key: String, enabled: Boolean, authentication: Authentication?): Map<String, Any?> {
        ensureSettings()
        val setting = settings.findByKey(key).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown OAuth setting")
        }
        setting.enabled = enabled
        setting.updatedAt = Instant.now()
        setting.updatedBy = authentication?.name ?: "system"
        settings.save(setting)
        audit.record(authentication, if (enabled) "OAUTH_SWITCH_ENABLED" else "OAUTH_SWITCH_DISABLED", "OAUTH_SETTING", key)
        return settingResponse(setting)
    }

    fun settings(): Map<String, Any?> {
        ensureSettings()
        return mapOf("settings" to settings.findAll().sortedBy { it.key }.map { settingResponse(it) })
    }

    private fun validateClientSecret(client: OAuthClientEntity, secret: String?) {
        if (!client.active || client.revokedAt != null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Client is disabled")
        if (client.clientType == OAuthClientType.PUBLIC) return
        if (secret.isNullOrBlank() || client.clientSecretHash.isNullOrBlank() || !encoder.matches(secret, client.clientSecretHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials")
        }
    }

    private fun parseEnvironment(value: String?): OAuthEnvironment =
        runCatching { OAuthEnvironment.valueOf((value ?: "SANDBOX").uppercase()) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported environment")
        }

    private fun requireSetting(key: String) {
        if (!settingEnabled(key)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "OAuth access is disabled by operator control: $key")
    }

    private fun settingEnabled(key: String): Boolean =
        settings.findByKey(key).orElse(null)?.enabled == true

    private fun ensureSettings() {
        val existing = settings.findAll().map { it.key }.toSet()
        val missing = knownSettings.filter { it.first !in existing }.map {
            OAuthSettingEntity(key = it.first, enabled = it.second)
        }
        if (missing.isNotEmpty()) settings.saveAll(missing)
    }

    /**
     * Validates redirect URIs at registration so the stored set is safe for the future
     * authorization-code flow's REQUIRED exact-string match (RFC 6749 §3.1.2, RFC 9700 §4.1.3):
     * every entry must be an absolute http(s) URI with a host, no fragment, and no wildcard.
     * This blocks open-redirect / javascript:/data: / wildcard registrations before they can ever
     * back a redirect. Duplicates are removed; ordering is otherwise preserved.
     */
    internal fun validateRedirectUris(uris: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in uris) {
            val value = raw.trim()
            if (value.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must not be blank")
            }
            if (value.contains("*")) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must not contain wildcards: $value")
            }
            val parsed = runCatching { URI(value) }.getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri is not a valid URI: $value")
            }
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must use http or https: $value")
            }
            if (!parsed.isAbsolute || parsed.host.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must be an absolute URI with a host: $value")
            }
            if (parsed.fragment != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must not contain a fragment: $value")
            }
            seen.add(value)
        }
        return seen.toList()
    }

    private fun parseScopeText(scopeText: String?): Set<String> =
        scopeText?.split(" ", ",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun parseJsonList(value: String): List<String> =
        runCatching { json.readValue(value, Array<String>::class.java).toList() }
            .getOrElse { value.split(",").map(String::trim).filter(String::isNotBlank) }

    private fun clientResponse(client: OAuthClientEntity, rawSecret: String?) = mapOf(
        "client_id" to client.clientId,
        "client_secret" to rawSecret,
        "display_name" to client.displayName,
        "client_type" to client.clientType.name,
        "owner_type" to client.ownerType.name,
        "owner_id" to client.ownerId,
        "owner_handle" to client.ownerHandle,
        "redirect_uris" to parseJsonList(client.redirectUris),
        "allowed_scopes" to parseJsonList(client.allowedScopes),
        "allowed_environments" to client.allowedEnvironments.split(",").filter { it.isNotBlank() },
        "active" to client.active,
        "mcp_enabled" to client.mcpEnabled,
        "live_enabled" to client.liveEnabled,
        "created_at" to client.createdAt,
        "updated_at" to client.updatedAt,
        "secret_rotated_at" to client.secretRotatedAt,
        "revoked_at" to client.revokedAt
    )

    private fun settingResponse(setting: OAuthSettingEntity) = mapOf(
        "key" to setting.key,
        "enabled" to setting.enabled,
        "updated_at" to setting.updatedAt,
        "updated_by" to setting.updatedBy,
        "note" to setting.note
    )

    private fun randomToken(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun supportedScopes(): List<String> = listOf(
        "astro:payments.create",
        "astro:payments.read",
        "astro:merchant.reports.read",
        "astro:merchant.webhooks.manage",
        "astro:bank.reports.read",
        "identity:registry.read",
        "identity:bank.aliases.read",
        "identity:customer.profile.read",
        "openwave:mcp.read",
        "openwave:mcp.write",
        "openwave:owner.ops.read"
    )
}

data class CreateOAuthClientRequest(
    val clientId: String? = null,
    val displayName: String,
    val clientType: OAuthClientType = OAuthClientType.CONFIDENTIAL,
    val ownerType: OAuthOwnerType = OAuthOwnerType.NEPTUNE,
    val ownerId: String? = null,
    val ownerHandle: String? = null,
    val redirectUris: List<String> = emptyList(),
    val allowedScopes: List<String> = emptyList(),
    val allowedEnvironments: List<String> = listOf("SANDBOX"),
    val active: Boolean = true,
    val mcpEnabled: Boolean = false,
    val liveEnabled: Boolean = false
)

data class UpdateOAuthClientRequest(
    val displayName: String? = null,
    val redirectUris: List<String>? = null,
    val allowedScopes: List<String>? = null,
    val allowedEnvironments: List<String>? = null,
    val active: Boolean? = null,
    val mcpEnabled: Boolean? = null,
    val liveEnabled: Boolean? = null
)
