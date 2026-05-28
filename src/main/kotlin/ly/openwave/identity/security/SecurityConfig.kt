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
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

const val ROLE_ADMIN       = "ROLE_ADMIN"
const val ROLE_BANK        = "ROLE_BANK"

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
                    "/banks",
                    "/banks/*",
                    "/auth/login",
                    "/registry/info",
                    "/actuator/health"
                ).permitAll()
                // Admin only
                it.requestMatchers("POST:/banks").hasRole("ADMIN")
                it.requestMatchers("PATCH:/banks/*").hasRole("ADMIN")
                it.requestMatchers("/portal-users/**").hasAnyRole("ADMIN", "BANK")
                // Bank-authenticated
                it.anyRequest().hasAnyRole("BANK", "ADMIN")
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
            adminKey != null && adminKey == props.adminKey -> {
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
