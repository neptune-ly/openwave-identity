package ly.openwave.identity.controller

import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.exception.RateLimitExceededException
import ly.openwave.identity.service.ResolutionService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RestController
class ResolutionController(
    private val resolutionService: ResolutionService,
    private val props: RegistryProperties
) {

    // Fixed-window, per-IP limiter for the unauthenticated public resolve endpoint.
    // Authenticated/internal callers bypass the limit. Window is one minute.
    private val window = RateLimiter(windowMillis = 60_000)

    @GetMapping("/identity/resolve")
    fun resolve(
        request: HttpServletRequest,
        @RequestParam alias: String,
        @RequestParam(required = false, defaultValue = "payment") purpose: String
    ): ResponseEntity<AliasResolutionResponse> {
        val authorized = isAuthorizedCaller()
        // Throttle only anonymous callers; authorized internal callers (bank/admin API key
        // or portal session) keep their existing unthrottled access for the payment hot path.
        if (!authorized) {
            val limit = props.resolveRateLimitPerMinute
            if (limit > 0 && !window.tryAcquire(clientIp(request), limit)) {
                throw RateLimitExceededException("Resolve rate limit exceeded for this client")
            }
        }

        val result = resolutionService.resolve(alias)
        // Unmask the IBAN only for authorized internal callers. Anonymous callers receive a
        // last-4 masked IBAN so the public endpoint cannot be used to harvest full account
        // numbers tied to a display name.
        val iban = if (authorized) result.iban else maskIbanLast4(result.iban)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS))
            .header("X-OpenWave-Registry-Version", "1.0.0")
            .body(
                AliasResolutionResponse(
                    nptHandle   = result.nptHandle,
                    bankHandle  = result.bankHandle,
                    iban        = iban,
                    displayName = result.displayName,
                    isDefault   = result.isDefault,
                    resolvedAt  = result.resolvedAt
                )
            )
    }

    private fun isAuthorizedCaller(): Boolean {
        val auth = SecurityContextHolder.getContext().authentication ?: return false
        // Spring populates an AnonymousAuthenticationToken (isAuthenticated == true,
        // ROLE_ANONYMOUS) for permitAll requests — that is NOT an authorized internal caller.
        if (auth is AnonymousAuthenticationToken) return false
        if (!auth.isAuthenticated) return false
        return auth.authorities.orEmpty().any { it.authority != "ROLE_ANONYMOUS" }
    }

    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",").last().trim().ifBlank { "unknown" }
        }
        return request.remoteAddr ?: "unknown"
    }

    private fun maskIbanLast4(iban: String): String {
        if (iban.length <= 4) return "****"
        return "****" + iban.takeLast(4)
    }
}

/**
 * Minimal in-memory fixed-window rate limiter keyed by client identifier (e.g. IP).
 * Per-process only; for multi-instance deployments place a shared limiter (gateway/WAF)
 * in front. Counters are reset lazily as windows roll over.
 */
class RateLimiter(private val windowMillis: Long, private val maxEntries: Int = 10_000) {
    private data class Counter(val windowStart: Long, val count: AtomicInteger)

    private val counters = ConcurrentHashMap<String, Counter>()
    private val nextPruneMs = AtomicLong(0)

    fun tryAcquire(key: String, limit: Int): Boolean {
        val now = System.currentTimeMillis()
        val boundedKey = key.take(240)
        while (true) {
            val existing = counters[boundedKey]
            if (existing == null || now - existing.windowStart >= windowMillis) {
                if (counters.size >= maxEntries.coerceAtLeast(1) && existing == null) {
                    pruneIfNeeded(now)
                    if (counters.size >= maxEntries.coerceAtLeast(1)) {
                        return false
                    }
                }
                val fresh = Counter(now, AtomicInteger(1))
                // Reset (or create) the window atomically; retry if another thread raced us.
                if (existing == null) {
                    if (counters.putIfAbsent(boundedKey, fresh) == null) return true
                } else {
                    if (counters.replace(boundedKey, existing, fresh)) return true
                }
                continue
            }
            return existing.count.incrementAndGet() <= limit
        }
    }

    private fun pruneIfNeeded(now: Long) {
        val max = maxEntries.coerceAtLeast(1)
        if (counters.size < max) return
        val next = nextPruneMs.get()
        if (now < next) return
        if (!nextPruneMs.compareAndSet(next, now + windowMillis)) return

        counters.entries
            .asSequence()
            .filter { now - it.value.windowStart >= windowMillis }
            .forEach { counters.remove(it.key, it.value) }

        if (counters.size < max) return
        val overflow = counters.size - max + 1
        counters.entries
            .asSequence()
            .sortedBy { it.value.windowStart }
            .take(overflow)
            .forEach { counters.remove(it.key, it.value) }
    }
}

data class AliasResolutionResponse(
    val nptHandle: String,
    val bankHandle: String,
    val iban: String,
    val displayName: String,
    val isDefault: Boolean,
    val resolvedAt: Instant
)
