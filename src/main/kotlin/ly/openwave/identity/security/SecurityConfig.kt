package ly.openwave.identity.security

import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.service.BankService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.http.HttpMethod
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val ROLE_ADMIN       = "ROLE_ADMIN"
const val ROLE_BANK        = "ROLE_BANK"
const val ROLE_CUSTOMER    = "ROLE_CUSTOMER"

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val props: RegistryProperties,
    private val bankService: BankService,
    private val portalTokenService: PortalTokenService
) {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = false
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Public endpoints — no auth
                it.requestMatchers(
                    "/identity/resolve",
                    "/auth/login",
                    "/auth/login/totp/verify",
                    "/auth/login/bank-approval/*",
                    "/auth/password-reset/request",
                    "/auth/password-reset/confirm",
                    "/auth/passkey/options/authenticate",
                    "/auth/passkey/authenticate",
                    "/registry/info",
                    "/.well-known/**",
                    "/oauth/token",
                    "/oauth/jwks",
                    "/oauth/authorize",
                    "/assets/**",
                    "/actuator/health"
                ).permitAll()
                // OAuth introspection (RFC 7662) and revocation (RFC 7009) endpoints.
                // They are not anonymous: the OAuthController authenticates the caller with
                // OAuth client credentials (client_id/secret, Basic or form) — a scheme the
                // Spring role filter does not model — and rejects unauthenticated callers with
                // 401. They are permitted past the servlet-security filter so that client-credential
                // auth (rather than ROLE_*) can be enforced in the controller.
                it.requestMatchers("/oauth/introspect", "/oauth/revoke").permitAll()
                it.requestMatchers("/oauth/admin/**").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.GET, "/banks/me").hasRole("BANK")
                it.requestMatchers(HttpMethod.GET, "/banks", "/banks/*").permitAll()
                it.requestMatchers(HttpMethod.PATCH, "/banks/me/branding").hasRole("BANK")
                it.requestMatchers(HttpMethod.POST, "/banks/me/branding/logo").hasRole("BANK")
                // Admin only
                it.requestMatchers(HttpMethod.POST, "/banks").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.PATCH, "/banks/**").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.POST, "/banks/*/branding/logo").hasRole("ADMIN")
                it.requestMatchers("/identity/internal/**").hasRole("ADMIN")
                it.requestMatchers("/portal/audit-events/**").hasRole("ADMIN")
                it.requestMatchers("/portal-users/**").hasAnyRole("ADMIN", "BANK")
                it.requestMatchers("/customer/**").hasRole("CUSTOMER")
                it.requestMatchers(
                    "/auth/profile",
                    "/auth/passkeys",
                    "/auth/passkeys/*",
                    "/auth/passkey/options/register",
                    "/auth/passkey/register",
                    "/auth/totp",
                    "/auth/totp/setup",
                    "/auth/totp/confirm",
                    "/auth/totp/disable"
                ).hasAnyRole("ADMIN", "BANK", "CUSTOMER")
                // Bank-authenticated
                it.anyRequest().hasAnyRole("BANK", "ADMIN", "CUSTOMER")
            }
            .addFilterBefore(ApiKeyFilter(props, bankService, portalTokenService), UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}

class ApiKeyFilter(
    private val props: RegistryProperties,
    private val bankService: BankService,
    private val portalTokenService: PortalTokenService
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val adminKey = req.getHeader("X-OpenWave-Registry-Key")
        val bankKey  = req.getHeader("X-OpenWave-Bank-Key")
        val portalSession = portalTokenService.verify(req.getHeader("X-OpenWave-Portal-Session"))

        when {
            portalSession?.role == "ADMIN" -> {
                val auth = UsernamePasswordAuthenticationToken(
                    portalSession.subject, null, listOf(SimpleGrantedAuthority(ROLE_ADMIN))
                )
                SecurityContextHolder.getContext().authentication = auth
            }
            portalSession?.role == "BANK" && portalSession.bankHandle != null -> {
                val bank = runCatching { bankService.getBank(portalSession.bankHandle) }.getOrNull()
                if (bank != null && bank.active) {
                    val auth = UsernamePasswordAuthenticationToken(
                        portalSession.subject, null, listOf(SimpleGrantedAuthority(ROLE_BANK))
                    )
                    auth.details = bank
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
            portalSession?.role == "CUSTOMER" -> {
                val auth = UsernamePasswordAuthenticationToken(
                    portalSession.subject, null, listOf(SimpleGrantedAuthority(ROLE_CUSTOMER))
                )
                SecurityContextHolder.getContext().authentication = auth
            }
            adminKey != null && constantTimeEquals(adminKey, props.adminKey) -> {
                val auth = UsernamePasswordAuthenticationToken(
                    "registry-admin", null, listOf(SimpleGrantedAuthority(ROLE_ADMIN))
                )
                SecurityContextHolder.getContext().authentication = auth
            }
            bankKey != null -> {
                val bank = bankService.resolveByApiKey(bankKey)
                if (bank != null && bank.active) {
                    val auth = UsernamePasswordAuthenticationToken(
                        bank.bankHandle, null, listOf(SimpleGrantedAuthority(ROLE_BANK))
                    )
                    auth.details = bank
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        chain.doFilter(req, res)
    }
}

fun callerBankHandle(): String =
    (SecurityContextHolder.getContext().authentication?.details as? ly.openwave.identity.entity.BankEntity)?.bankHandle
        ?: throw ly.openwave.identity.exception.ForbiddenException()

/**
 * Constant-time comparison of a presented secret against the configured one.
 * A blank configured secret never matches (prevents accidental open admin access),
 * and MessageDigest.isEqual removes the early-exit timing side channel of `==`/equals.
 */
internal fun constantTimeEquals(presented: String, configured: String): Boolean {
    if (configured.isEmpty()) return false
    val a = presented.toByteArray(StandardCharsets.UTF_8)
    val b = configured.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(a, b)
}
