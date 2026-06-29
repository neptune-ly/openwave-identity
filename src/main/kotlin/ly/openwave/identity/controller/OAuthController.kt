package ly.openwave.identity.controller

import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.service.CreateOAuthClientRequest
import ly.openwave.identity.service.OpenWaveOAuthService
import ly.openwave.identity.service.UpdateOAuthClientRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.util.Base64

@RestController
class OAuthController(
    private val oauth: OpenWaveOAuthService
) {
    @GetMapping("/.well-known/openid-configuration")
    fun openIdConfiguration(request: HttpServletRequest): Map<String, Any?> =
        oauth.discovery(baseUrl(request))

    @GetMapping("/.well-known/oauth-protected-resource")
    fun protectedResource(request: HttpServletRequest): Map<String, Any?> =
        oauth.protectedResource(baseUrl(request))

    @GetMapping("/oauth/jwks")
    fun jwks(): Map<String, Any?> = oauth.jwks()

    @GetMapping("/oauth/authorize")
    fun authorizePlaceholder(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(mapOf(
            "error" to "authorization_code_not_enabled",
            "message" to "Authorization code + PKCE is reserved for the delegated-access UI milestone. Use client_credentials for v1 server-side and MCP access."
        ))

    @PostMapping("/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun token(
        request: HttpServletRequest,
        @RequestParam("grant_type") grantType: String,
        @RequestParam("client_id", required = false) clientIdParam: String?,
        @RequestParam("client_secret", required = false) clientSecretParam: String?,
        @RequestParam("scope", required = false) scope: String?,
        @RequestParam("audience", required = false) audience: String?,
        @RequestParam("environment", required = false) environment: String?,
        @RequestParam("refresh_token", required = false) refreshToken: String?
    ): Map<String, Any?> {
        val basic = basicClientCredentials(request)
        val clientId = basic?.first ?: clientIdParam
        val clientSecret = basic?.second ?: clientSecretParam
        val issued = when (grantType) {
            "client_credentials" -> oauth.issueClientCredentials(
                clientId = clientId ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "client_id is required"),
                clientSecret = clientSecret,
                scopeText = scope,
                audience = audience,
                environmentText = environment
            )
            "refresh_token" -> oauth.refresh(
                refreshToken = refreshToken ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "refresh_token is required"),
                scopeText = scope
            )
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported grant_type")
        }
        return mapOf(
            "access_token" to issued.accessToken,
            "refresh_token" to issued.refreshToken,
            "token_type" to issued.tokenType,
            "expires_in" to issued.expiresIn,
            "scope" to issued.scope
        )
    }

    @PostMapping("/oauth/introspect", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun introspect(
        request: HttpServletRequest,
        @RequestParam token: String,
        @RequestParam("client_id", required = false) clientIdParam: String?,
        @RequestParam("client_secret", required = false) clientSecretParam: String?,
        @RequestParam("audience", required = false) audience: String?
    ): Map<String, Any?> {
        val basic = basicClientCredentials(request)
        // RFC 7662 §2.1: the introspection endpoint MUST require authentication.
        oauth.authenticateClient(basic?.first ?: clientIdParam, basic?.second ?: clientSecretParam)
        return oauth.introspect(token, audience)
    }

    @PostMapping("/oauth/revoke", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun revoke(
        request: HttpServletRequest,
        @RequestParam token: String,
        @RequestParam("client_id", required = false) clientIdParam: String?,
        @RequestParam("client_secret", required = false) clientSecretParam: String?
    ): Map<String, Any?> {
        val basic = basicClientCredentials(request)
        // RFC 7009 §2.1: the revocation endpoint MUST require client authentication,
        // and a client may only revoke its own tokens.
        val client = oauth.authenticateClient(basic?.first ?: clientIdParam, basic?.second ?: clientSecretParam)
        return oauth.revoke(token, client)
    }

    @GetMapping("/oauth/admin/settings")
    fun settings(): Map<String, Any?> = oauth.settings()

    @PatchMapping("/oauth/admin/settings")
    fun patchSettings(@RequestBody req: Map<String, Boolean>, authentication: Authentication?): Map<String, Any?> {
        val changed = req.map { (key, enabled) -> oauth.setSwitch(key, enabled, authentication) }
        return mapOf("settings" to changed)
    }

    @PostMapping("/oauth/admin/kill-switches/{switchKey}/enable")
    fun enableSwitch(@PathVariable switchKey: String, authentication: Authentication?): Map<String, Any?> =
        oauth.setSwitch(switchKey, true, authentication)

    @PostMapping("/oauth/admin/kill-switches/{switchKey}/disable")
    fun disableSwitch(@PathVariable switchKey: String, authentication: Authentication?): Map<String, Any?> =
        oauth.setSwitch(switchKey, false, authentication)

    @GetMapping("/oauth/admin/clients")
    fun clients(): Map<String, Any?> = mapOf("clients" to oauth.listClients())

    @PostMapping("/oauth/admin/clients")
    fun createClient(@RequestBody req: CreateOAuthClientRequest, authentication: Authentication?): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.CREATED).body(oauth.createClient(req, authentication))

    @PatchMapping("/oauth/admin/clients/{clientId}")
    fun updateClient(
        @PathVariable clientId: String,
        @RequestBody req: UpdateOAuthClientRequest,
        authentication: Authentication?
    ): Map<String, Any?> = oauth.updateClient(clientId, req, authentication)

    @PostMapping("/oauth/admin/clients/{clientId}/rotate-secret")
    fun rotateSecret(@PathVariable clientId: String, authentication: Authentication?): Map<String, Any?> =
        oauth.rotateClientSecret(clientId, authentication)

    @PostMapping("/oauth/admin/clients/{clientId}/revoke-tokens")
    fun revokeClientTokens(@PathVariable clientId: String, authentication: Authentication?): Map<String, Any?> =
        oauth.revokeClientTokens(clientId, authentication)

    @PostMapping("/oauth/admin/users/{userId}/revoke-grants")
    fun revokeUserGrants(@PathVariable userId: String, authentication: Authentication?): Map<String, Any?> =
        oauth.revokeUserGrants(userId, authentication)

    private fun baseUrl(request: HttpServletRequest): String {
        val forwardedProto = request.getHeader("X-Forwarded-Proto")
        val forwardedHost = request.getHeader("X-Forwarded-Host")
        val scheme = forwardedProto ?: request.scheme
        val host = forwardedHost ?: request.serverName + if (request.serverPort in listOf(80, 443)) "" else ":${request.serverPort}"
        return "$scheme://$host${request.contextPath}".trimEnd('/')
    }

    private fun basicClientCredentials(request: HttpServletRequest): Pair<String, String>? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = String(Base64.getDecoder().decode(header.substringAfter(" ")), StandardCharsets.UTF_8)
        val index = decoded.indexOf(':')
        if (index <= 0) return null
        return decoded.substring(0, index) to decoded.substring(index + 1)
    }
}
