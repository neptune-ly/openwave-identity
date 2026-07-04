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
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

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
    private val authorizationRequests: OAuthAuthorizationRequestRepository,
    private val audit: PortalAuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()
    private val json = jacksonObjectMapper()
    private val nextAuthorizationRequestCleanupMs = AtomicLong(0)
    private val authorizationRequestCleanupCooldownMs = 60_000L
    private val pkceCodeChallengeS256Pattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val pkceVerifierPattern = Regex("^[A-Za-z0-9\\-._~]{43,128}$")
    private val accessTokenPattern = Regex("^owat_[A-Za-z0-9_-]+$")
    private val refreshTokenPattern = Regex("^owrt_[A-Za-z0-9_-]+$")
    private val authorizationCodePattern = Regex("^owac_[A-Za-z0-9_-]+$")
    private val maxClientIdLength = 80
    private val maxClientSecretLength = 512
    private val maxRequestIdLength = 200
    private val maxRedirectUriLength = 2048
    private val maxScopeTextLength = 512
    private val maxCodeLength = 320
    private val maxAudienceLength = 80
    private val maxEnvironmentTextLength = 24
    private val maxStateLength = 500
    private val maxTokenLength = 2048

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
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code", "client_credentials", "refresh_token"),
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
        validateRequiredField(clientId, "client_id", maxClientIdLength)
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
    fun createAuthorizationRequest(
        clientId: String,
        redirectUri: String,
        responseType: String,
        scopeText: String,
        codeChallenge: String,
        codeChallengeMethod: String?,
        state: String?,
        audience: String?,
        environmentText: String?
    ): Map<String, Any?> {
        validateRequiredField(clientId, "client_id", maxClientIdLength)
        validateRequiredField(redirectUri, "redirect_uri", maxRedirectUriLength)
        validateRequiredField(responseType, "response_type", 20)
        validateOptionalField(scopeText, "scope", maxScopeTextLength)
        validateRequiredField(codeChallenge, "code_challenge", maxLength = 320, minLength = 43)
        validateOptionalField(codeChallengeMethod, "code_challenge_method", 20)
        validateOptionalField(state, "state", maxStateLength)
        validateOptionalField(audience, "audience", maxAudienceLength)
        validateOptionalField(environmentText, "environment", maxEnvironmentTextLength)

        ensureSettings()
        requireSetting("oauth.global")
        purgeExpiredAuthorizationRequests()
        if (responseType != "code") throw ResponseStatusException(HttpStatus.BAD_REQUEST, "response_type must be code")
        if ((codeChallengeMethod ?: "S256") != "S256") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PKCE S256 is supported")
        }
        if (codeChallenge.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "code_challenge is required")
        if (!pkceCodeChallengeS256Pattern.matches(codeChallenge)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "code_challenge is invalid")
        }
        val client = clients.findByClientId(clientId) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown OAuth client")
        validateClientOperationalGates(client, environmentText)
        val redirects = parseJsonList(client.redirectUris)
        if (redirectUri !in redirects) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri is not registered for this client")
        }
        val requested = parseScopeText(scopeText)
        if (requested.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scope is required")
        val allowed = parseJsonList(client.allowedScopes).toSet()
        val granted = requested.filter { it in allowed }.toSet()
        if (granted.size != requested.size || granted.isEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Requested scope is not allowed for this client")
        }
        val environment = parseEnvironment(environmentText)
        val request = authorizationRequests.save(
            OAuthAuthorizationRequestEntity(
                requestId = "owar_${randomToken(18)}",
                clientId = client.clientId,
                redirectUri = redirectUri,
                scopes = granted.joinToString(" "),
                audience = audience?.takeIf { it.isNotBlank() } ?: "astro",
                environment = environment,
                state = state?.takeIf { it.isNotBlank() },
                codeChallenge = codeChallenge,
                codeChallengeMethod = "S256",
                requestExpiresAt = Instant.now().plusSeconds(600)
            )
        )
        audit.record(null, "OAUTH_AUTHORIZATION_REQUEST_CREATED", "OAUTH_CLIENT", client.clientId, mapOf("request_id" to request.requestId))
        return mapOf(
            "request_id" to request.requestId,
            "consent_url" to "/portal/oauth-consent?request_id=${urlEncode(request.requestId)}",
            "expires_at" to request.requestExpiresAt
        )
    }

    private fun purgeExpiredAuthorizationRequests() {
        val nowMs = System.currentTimeMillis()
        val nextCleanupMs = nextAuthorizationRequestCleanupMs.get()
        if (nowMs < nextCleanupMs) {
            return
        }
        if (!nextAuthorizationRequestCleanupMs.compareAndSet(nextCleanupMs, nowMs + authorizationRequestCleanupCooldownMs)) {
            return
        }
        runCatching {
            val deleted = authorizationRequests.deleteExpired(Instant.now())
            if (deleted > 0) {
                log.info("Purged {} expired OAuth authorization requests.", deleted)
            }
        }.onFailure { ex ->
            nextAuthorizationRequestCleanupMs.set(nowMs)
            log.warn("Failed to purge expired OAuth authorization requests.", ex)
        }
    }

    @Transactional
    fun consentRequest(requestId: String, authentication: Authentication?): Map<String, Any?> {
        val req = authorizationRequests.findById(requestId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth consent request not found")
        }
        expireIfNeeded(req)
        val role = portalRole(authentication)
        val scopes = req.scopes.split(" ").filter { it.isNotBlank() }
        if (!roleAllowedForScopes(role, scopes)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This portal role cannot approve the requested scopes")
        }
        val client = clients.findByClientId(req.clientId)
        return consentRequestResponse(req, client)
    }

    @Transactional
    fun approveConsentRequest(requestId: String, authentication: Authentication?): Map<String, Any?> {
        val req = authorizationRequests.findById(requestId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth consent request not found")
        }
        expireIfNeeded(req)
        if (req.status != OAuthAuthorizationStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "OAuth consent request is not pending")
        }
        val role = portalRole(authentication)
        val scopes = req.scopes.split(" ").filter { it.isNotBlank() }
        if (!roleAllowedForScopes(role, scopes)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This portal role cannot approve the requested scopes")
        }
        val client = clients.findByClientId(req.clientId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth client not found")
        val subject = authentication?.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Portal session required")
        val now = Instant.now()
        val grant = grants.save(
            OAuthUserGrantEntity(
                subject = subject,
                clientId = client.clientId,
                scopes = req.scopes,
                audience = req.audience,
                environment = req.environment,
                ownerType = client.ownerType,
                ownerId = client.ownerId,
                ownerHandle = client.ownerHandle,
                approvedBy = subject
            )
        )
        val code = "owac_${randomToken(32)}"
        req.subject = subject
        req.subjectRole = role
        req.status = OAuthAuthorizationStatus.APPROVED
        req.authorizationCodeHash = sha256(code)
        req.codeExpiresAt = now.plusSeconds(600)
        req.approvedAt = now
        req.updatedAt = now
        req.grantId = grant.id
        authorizationRequests.save(req)
        audit.record(authentication, "OAUTH_CONSENT_APPROVED", "OAUTH_CLIENT", client.clientId, mapOf("request_id" to requestId, "grant_id" to grant.id))
        return mapOf(
            "request_id" to requestId,
            "status" to req.status.name,
            "redirect_url" to redirectWith(req.redirectUri, mapOf("code" to code, "state" to req.state))
        )
    }

    @Transactional
    fun rejectConsentRequest(requestId: String, authentication: Authentication?): Map<String, Any?> {
        val req = authorizationRequests.findById(requestId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth consent request not found")
        }
        expireIfNeeded(req)
        if (req.status != OAuthAuthorizationStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "OAuth consent request is not pending")
        }
        val role = portalRole(authentication)
        val scopes = req.scopes.split(" ").filter { it.isNotBlank() }
        if (!roleAllowedForScopes(role, scopes)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This portal role cannot reject the requested scopes")
        }
        req.status = OAuthAuthorizationStatus.REJECTED
        req.rejectedAt = Instant.now()
        req.updatedAt = req.rejectedAt!!
        authorizationRequests.save(req)
        audit.record(authentication, "OAUTH_CONSENT_REJECTED", "OAUTH_CLIENT", req.clientId, mapOf("request_id" to requestId))
        return mapOf(
            "request_id" to requestId,
            "status" to req.status.name,
            "redirect_url" to redirectWith(req.redirectUri, mapOf("error" to "access_denied", "state" to req.state))
        )
    }

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

    fun listGrants(authentication: Authentication?): List<Map<String, Any?>> {
        val role = portalRole(authentication)
        if (role != "ADMIN") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Registry admin required")
        return grants.findAll().sortedByDescending { it.createdAt }.map { grantResponse(it) }
    }

    fun listCustomerGrants(authentication: Authentication?): List<Map<String, Any?>> {
        val role = portalRole(authentication)
        if (role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        val subject = authentication?.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Portal session required")
        return grants.findAllBySubject(subject).sortedByDescending { it.createdAt }.map { grantResponse(it) }
    }

    @Transactional
    fun revokeGrant(grantId: Long, authentication: Authentication?, customerOnly: Boolean = false): Map<String, Any?> {
        val role = portalRole(authentication)
        if (customerOnly && role != "CUSTOMER") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer session required")
        if (!customerOnly && role != "ADMIN") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Registry admin required")
        val grant = grants.findById(grantId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth grant not found") }
        if (customerOnly && grant.subject != authentication?.name) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth grant not found")
        }
        val now = Instant.now()
        if (grant.active) {
            grant.active = false
            grant.revokedAt = now
            grant.revokedBy = authentication?.name ?: "system"
            grants.save(grant)
        }
        val tokenRows = tokens.findAllBySubject(grant.subject).filter { it.clientId == grant.clientId }
        tokenRows.filter { it.revokedAt == null }.forEach {
            it.revokedAt = now
            it.revokeReason = "grant_revoked"
        }
        tokens.saveAll(tokenRows)
        audit.record(authentication, "OAUTH_GRANT_REVOKED", "OAUTH_GRANT", grantId.toString(), mapOf("tokens" to tokenRows.size))
        return mapOf("grant" to grantResponse(grant), "revoked_tokens" to tokenRows.size)
    }

    @Transactional
    fun issueClientCredentials(
        clientId: String,
        clientSecret: String?,
        scopeText: String?,
        audience: String?,
        environmentText: String?
    ): OAuthTokenIssueResult {
        validateRequiredField(clientId, "client_id", maxClientIdLength)
        validateOptionalField(clientSecret, "client_secret", maxClientSecretLength)
        validateOptionalField(scopeText, "scope", maxScopeTextLength)
        validateOptionalField(audience, "audience", maxAudienceLength)
        validateOptionalField(environmentText, "environment", maxEnvironmentTextLength)

        ensureSettings()
        requireSetting("oauth.global")
        val client = clients.findByClientId(clientId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client")
        validateClientSecret(client, clientSecret)
        val environment = validateClientOperationalGates(client, environmentText)
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
                subjectRole = "CLIENT",
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

    @Transactional
    fun exchangeAuthorizationCode(
        clientId: String,
        clientSecret: String?,
        code: String,
        redirectUri: String,
        codeVerifier: String
    ): OAuthTokenIssueResult {
        validateRequiredField(clientId, "client_id", maxClientIdLength)
        validateOptionalField(clientSecret, "client_secret", maxClientSecretLength)
        validateRequiredField(code, "code", maxCodeLength)
        validateRequiredField(redirectUri, "redirect_uri", maxRedirectUriLength)
        validateRequiredField(codeVerifier, "code_verifier", maxCodeLength, minLength = 43)
        validateTokenShape(code, "code", authorizationCodePattern)

        ensureSettings()
        requireSetting("oauth.global")
        val client = authenticateClient(clientId, clientSecret)
        val req = authorizationRequests.findByAuthorizationCodeHash(sha256(code))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authorization code")
        if (req.clientId != client.clientId) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authorization code")
        if (req.status != OAuthAuthorizationStatus.APPROVED || req.exchangedAt != null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization code has already been used")
        }
        if (Instant.now().isAfter(req.codeExpiresAt ?: req.requestExpiresAt)) {
            req.status = OAuthAuthorizationStatus.EXPIRED
            req.updatedAt = Instant.now()
            authorizationRequests.save(req)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization code expired")
        }
        if (redirectUri != req.redirectUri) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri does not match authorization request")
        }
        if (!pkceS256Matches(codeVerifier, req.codeChallenge)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PKCE verification failed")
        }
        validateClientOperationalGates(client, req.environment.name)
        val grantId = req.grantId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OAuth grant is missing")
        val grant = grants.findById(grantId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "OAuth grant is missing") }
        if (!grant.active) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OAuth grant was revoked")
        val accessToken = "owat_${randomToken(40)}"
        val refreshToken = "owrt_${randomToken(40)}"
        val now = Instant.now()
        req.status = OAuthAuthorizationStatus.EXCHANGED
        req.exchangedAt = now
        req.updatedAt = now
        authorizationRequests.save(req)
        tokens.save(
            OAuthTokenEntity(
                tokenHash = sha256(accessToken),
                refreshTokenHash = sha256(refreshToken),
                clientId = client.clientId,
                subject = req.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OAuth subject is missing"),
                subjectRole = req.subjectRole,
                audience = req.audience,
                scopes = req.scopes,
                ownerType = client.ownerType,
                ownerId = client.ownerId,
                ownerHandle = client.ownerHandle,
                environment = req.environment,
                grantType = "authorization_code",
                grantId = grant.id,
                issuedAt = now,
                expiresAt = now.plusSeconds(props.accessTokenTtlSeconds),
                refreshExpiresAt = now.plusSeconds(props.refreshTokenTtlSeconds)
            )
        )
        return OAuthTokenIssueResult(accessToken, refreshToken, props.accessTokenTtlSeconds, req.scopes)
    }

    // noRollbackFor: when refresh-token reuse is detected we revoke the active token family and
    // THEN throw 401. Without this, Spring would roll back the family-revocation along with the
    // exception, leaving the leaked descendant usable. The legitimate failure throws below write
    // nothing, so suppressing rollback for them is harmless.
    @Transactional(noRollbackFor = [ResponseStatusException::class])
    fun refresh(refreshToken: String, clientId: String?, clientSecret: String?, scopeText: String?): OAuthTokenIssueResult {
        validateRequiredField(refreshToken, "refresh_token", maxTokenLength)
        validateOptionalField(clientId, "client_id", maxClientIdLength)
        validateOptionalField(clientSecret, "client_secret", maxClientSecretLength)
        validateOptionalField(scopeText, "scope", maxScopeTextLength)
        validateTokenShape(refreshToken, "refresh_token", refreshTokenPattern)

        ensureSettings()
        requireSetting("oauth.global")
        val row = tokens.findByRefreshTokenHash(sha256(refreshToken))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        val client = authenticateClient(clientId, clientSecret)
        if (client.clientId != row.clientId) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token was not issued to this client")
        }
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
                subjectRole = source.subjectRole,
                audience = source.audience,
                scopes = scopes.joinToString(" "),
                ownerType = source.ownerType,
                ownerId = source.ownerId,
                ownerHandle = source.ownerHandle,
                environment = source.environment,
                grantType = "refresh_token",
                grantId = source.grantId,
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
        validateRequiredField(clientId, "client_id", maxClientIdLength)
        validateOptionalField(clientSecret, "client_secret", maxClientSecretLength)
        val client = clients.findByClientId(clientId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials")
        validateClientSecret(client, clientSecret)
        return client
    }

    @Transactional
    fun revoke(token: String, client: OAuthClientEntity? = null): Map<String, Any?> {
        validateRequiredField(token, "token", maxTokenLength)
        validateRevocableTokenShape(token)
        val hash = sha256(token)
        val row = when {
            accessTokenPattern.matches(token) -> tokens.findByTokenHash(hash)
            refreshTokenPattern.matches(token) -> tokens.findByRefreshTokenHash(hash)
            else -> null
        } ?: return mapOf("revoked" to true)
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

    fun introspect(token: String, requiredAudience: String? = null, caller: OAuthClientEntity): Map<String, Any?> {
        validateRequiredField(token, "token", maxTokenLength)
        validateOptionalField(requiredAudience, "audience", maxAudienceLength)
        validateTokenShape(token, "token", accessTokenPattern)
        ensureSettings()
        val row = tokens.findByTokenHash(sha256(token)) ?: return mapOf("active" to false)
        val client = clients.findByClientId(row.clientId)
        val callerMayRead = caller.clientId == row.clientId || canResourceServerIntrospect(caller)
        if (!callerMayRead) return mapOf("active" to false)
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
                "subject_role" to row.subjectRole,
                "aud" to row.audience,
                "scope" to row.scopes,
                "client_id" to row.clientId,
                "grant_id" to row.grantId,
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

    private fun validateRequiredField(value: String?, fieldName: String, maxLength: Int, minLength: Int = 1) {
        if (value.isNullOrBlank() || value.length < minLength || value.length > maxLength) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is invalid")
        }
    }

    private fun validateOptionalField(value: String?, fieldName: String, maxLength: Int) {
        if (value != null && value.length > maxLength) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is too long")
        }
    }

    private fun validateTokenShape(value: String, fieldName: String, pattern: Regex) {
        if (!pattern.matches(value)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is invalid")
        }
    }

    private fun validateRevocableTokenShape(value: String) {
        if (!accessTokenPattern.matches(value) && !refreshTokenPattern.matches(value)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "token is invalid")
        }
    }

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

    private fun consentRequestResponse(req: OAuthAuthorizationRequestEntity, client: OAuthClientEntity?) = mapOf(
        "request_id" to req.requestId,
        "client_id" to req.clientId,
        "client_name" to (client?.displayName ?: req.clientId),
        "client_type" to client?.clientType?.name,
        "owner_type" to client?.ownerType?.name,
        "owner_id" to client?.ownerId,
        "owner_handle" to client?.ownerHandle,
        "redirect_uri" to req.redirectUri,
        "scopes" to req.scopes.split(" ").filter { it.isNotBlank() },
        "audience" to req.audience,
        "environment" to req.environment.name,
        "state_present" to !req.state.isNullOrBlank(),
        "status" to req.status.name,
        "request_expires_at" to req.requestExpiresAt,
        "code_expires_at" to req.codeExpiresAt,
        "approved_at" to req.approvedAt,
        "rejected_at" to req.rejectedAt,
        "exchanged_at" to req.exchangedAt
    )

    private fun grantResponse(grant: OAuthUserGrantEntity) = mapOf(
        "grant_id" to grant.id,
        "subject" to grant.subject,
        "client_id" to grant.clientId,
        "scopes" to grant.scopes.split(" ").filter { it.isNotBlank() },
        "audience" to grant.audience,
        "environment" to grant.environment.name,
        "owner_type" to grant.ownerType.name,
        "owner_id" to grant.ownerId,
        "owner_handle" to grant.ownerHandle,
        "active" to grant.active,
        "created_at" to grant.createdAt,
        "revoked_at" to grant.revokedAt,
        "revoked_by" to grant.revokedBy
    )

    private fun expireIfNeeded(req: OAuthAuthorizationRequestEntity) {
        if (req.status == OAuthAuthorizationStatus.PENDING && Instant.now().isAfter(req.requestExpiresAt)) {
            req.status = OAuthAuthorizationStatus.EXPIRED
            req.updatedAt = Instant.now()
            authorizationRequests.save(req)
        }
    }

    private fun validateClientOperationalGates(client: OAuthClientEntity, environmentText: String?): OAuthEnvironment {
        if (!client.active || client.revokedAt != null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Client is disabled")
        }
        val environment = parseEnvironment(environmentText)
        requireSetting("environment.${environment.name.lowercase()}")
        requireSetting("owner.${client.ownerType.name}")
        val allowedEnvironments = client.allowedEnvironments.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
        if (environment.name !in allowedEnvironments) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Environment is not allowed for this client")
        }
        if (environment == OAuthEnvironment.LIVE && !client.liveEnabled) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Live OAuth access is disabled for this client")
        }
        return environment
    }

    private fun portalRole(authentication: Authentication?): String {
        val role = authentication?.authorities
            ?.map { it.authority.removePrefix("ROLE_") }
            ?.firstOrNull { it in setOf("ADMIN", "BANK", "CUSTOMER") }
        return role ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Portal session required")
    }

    private fun roleAllowedForScopes(role: String, scopes: List<String>): Boolean {
        if (scopes.any { it == "openwave:tokens.introspect" || it.startsWith("openwave:mcp") || it.startsWith("openwave:owner") }) {
            return role == "ADMIN"
        }
        if (scopes.any { it.startsWith("astro:") && !it.startsWith("astro:bank.") }) {
            return role == "ADMIN"
        }
        if (scopes.any { it.startsWith("identity:bank.") || it.startsWith("astro:bank.") }) {
            return role == "ADMIN" || role == "BANK"
        }
        if (scopes.any { it.startsWith("identity:customer.") }) {
            return role == "CUSTOMER"
        }
        return role == "ADMIN"
    }

    private fun canResourceServerIntrospect(client: OAuthClientEntity): Boolean =
        client.clientType == OAuthClientType.RESOURCE_SERVER &&
            client.active &&
            client.revokedAt == null &&
            "openwave:tokens.introspect" in parseJsonList(client.allowedScopes)

    private fun pkceS256Matches(verifier: String, challenge: String): Boolean {
        if (!pkceVerifierPattern.matches(verifier)) return false
        val value = verifier
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return MessageDigest.isEqual(computed.toByteArray(), challenge.toByteArray())
    }

    private fun redirectWith(base: String, params: Map<String, String?>): String {
        val query = params.entries
            .filter { !it.value.isNullOrBlank() }
            .joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value!!)}" }
        if (query.isBlank()) return base
        return base + if (base.contains("?")) "&$query" else "?$query"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

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
        "openwave:tokens.introspect",
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
