package ly.openwave.identity.controller

import ly.openwave.identity.entity.BankLoginChallengeStatus
import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalBankLoginChallengeRepository
import ly.openwave.identity.repository.PortalUserRepository
import ly.openwave.identity.security.PortalTokenService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/portal")
class PortalOverviewController(
    private val portalTokenService: PortalTokenService,
    private val bankRepository: BankRepository,
    private val identityRepository: IdentityRepository,
    private val linkedAccountRepository: LinkedAccountRepository,
    private val portalUserRepository: PortalUserRepository,
    private val portalBankLoginChallengeRepository: PortalBankLoginChallengeRepository
) {

    @GetMapping("/overview")
    fun overview(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any?> {
        val principal = portalTokenService.verify(session)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        if (principal.role != "ADMIN") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Registry admin session required")
        }

        val now = Instant.now()
        val activeBanks = bankRepository.countByActiveTrue()
        val activeIdentities = identityRepository.countByStatus(IdentityStatus.ACTIVE)
        val suspendedIdentities = identityRepository.countByStatusNot(IdentityStatus.ACTIVE)
        val missingDefaultRoute = identityRepository.countByDefaultBankHandleIsNullAndStatus(IdentityStatus.ACTIVE)
        val totalAccounts = linkedAccountRepository.count()
        val defaultedAccounts = linkedAccountRepository.countByIsDefaultTrue()
        val totalPortalUsers = portalUserRepository.count()
        val activePortalUsers = portalUserRepository.countByActiveTrue()
        val customerUsers = portalUserRepository.countByRole(PortalRole.CUSTOMER)
        val activeCustomerUsers = portalUserRepository.countByRoleAndActiveTrue(PortalRole.CUSTOMER)
        val bankUsers = portalUserRepository.countByRole(PortalRole.BANK_ADMIN) +
            portalUserRepository.countByRole(PortalRole.BANK_OPERATOR) +
            portalUserRepository.countByRole(PortalRole.BANK_VIEWER)
        val registryUsers = portalUserRepository.countByRole(PortalRole.REGISTRY_ADMIN) +
            portalUserRepository.countByRole(PortalRole.REGISTRY_OPERATOR)
        val pendingLoginApprovals = portalBankLoginChallengeRepository.countByStatusAndExpiresAtAfter(
            BankLoginChallengeStatus.PENDING,
            now
        )
        val banks = bankRepository.findAll()
        val banksMissingOpsContact = banks.count { it.contactEmail.isBlank() && it.supportEmail.isNullOrBlank() }
        val banksMissingBrandSignal = banks.count { it.logoUrl.isNullOrBlank() && it.brandColor.isNullOrBlank() }
        val banksMissingWebsite = banks.count { it.website.isNullOrBlank() }

        val readinessChecks = listOf(
            mapOf("key" to "active_banks", "label" to "Active banks", "done" to (activeBanks > 0), "detail" to "$activeBanks active"),
            mapOf("key" to "customer_users", "label" to "Customer portal users", "done" to (activeCustomerUsers > 0), "detail" to "$activeCustomerUsers active of $customerUsers total"),
            mapOf("key" to "bank_contacts", "label" to "Bank support contacts", "done" to (banksMissingOpsContact == 0), "detail" to "$banksMissingOpsContact bank(s) missing visible operations contact"),
            mapOf("key" to "bank_brand_signal", "label" to "Bank brand signals", "done" to (banksMissingBrandSignal == 0), "detail" to "$banksMissingBrandSignal bank(s) missing logo or brand color"),
            mapOf("key" to "default_routes", "label" to "Default routes", "done" to (missingDefaultRoute == 0L), "detail" to "$missingDefaultRoute active identity record(s) missing default bank route"),
            mapOf("key" to "pending_approvals", "label" to "Pending bank approvals", "done" to (pendingLoginApprovals == 0L), "detail" to "$pendingLoginApprovals pending approval request(s)")
        )
        val readinessDone = readinessChecks.count { it["done"] == true }
        val nextSteps = buildList {
            if (banksMissingOpsContact > 0) add("Complete support or operations email visibility for every participating bank.")
            if (banksMissingBrandSignal > 0) add("Add a logo or brand color for banks that still render without a clear public identity signal.")
            if (banksMissingWebsite > 0) add("Add public website references for banks that still lack a visible directory website.")
            if (missingDefaultRoute > 0) add("Review active identities with no default bank route before broader bare-handle reliance.")
            if (pendingLoginApprovals > 0) add("Monitor pending phone or national-ID login approvals so customer access does not stall.")
            if (activeCustomerUsers == 0L) add("Customer portal access has not been activated for any identity record yet.")
        }.ifEmpty {
            listOf("Registry operations package is ready.")
        }

        return mapOf(
            "package" to mapOf(
                "registry" to mapOf(
                    "registered_banks" to bankRepository.count(),
                    "active_banks" to activeBanks,
                    "active_identities" to activeIdentities,
                    "inactive_or_suspended_identities" to suspendedIdentities,
                    "linked_accounts" to totalAccounts,
                    "defaulted_accounts" to defaultedAccounts,
                    "active_identities_missing_default_bank" to missingDefaultRoute
                ),
                "portal_access" to mapOf(
                    "total_portal_users" to totalPortalUsers,
                    "active_portal_users" to activePortalUsers,
                    "registry_users" to registryUsers,
                    "bank_users" to bankUsers,
                    "customer_users" to customerUsers,
                    "active_customer_users" to activeCustomerUsers
                ),
                "queues" to mapOf(
                    "pending_bank_login_approvals" to pendingLoginApprovals,
                    "banks_missing_operations_contact" to banksMissingOpsContact,
                    "banks_missing_brand_signal" to banksMissingBrandSignal,
                    "banks_missing_website" to banksMissingWebsite
                ),
                "readiness" to mapOf(
                    "done" to readinessDone,
                    "total" to readinessChecks.size,
                    "checks" to readinessChecks
                ),
                "next_steps" to nextSteps
            ),
            "generated_at" to now
        )
    }
}
