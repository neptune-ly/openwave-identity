package ly.openwave.identity.service

import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.entity.RetiredHandleEntity
import ly.openwave.identity.exception.BankNotFoundException
import ly.openwave.identity.exception.HandleInvalidFormatException
import ly.openwave.identity.exception.HandleRenameNotPermittedException
import ly.openwave.identity.exception.HandleRenameTooSoonException
import ly.openwave.identity.exception.HandleRetiredException
import ly.openwave.identity.exception.HandleTakenException
import ly.openwave.identity.exception.IdentityNotFoundException
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.RetiredHandleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A CUSTOMER CAN CHANGE THEIR NAME. THE OLD ONE NEVER BELONGS TO ANYONE ELSE.
 *
 * There was no rename path at all before this. `claimHandle` looks the customer
 * up by national ID and throws HandleTaken whenever the stored handle differs
 * from the requested one, so a customer's SECOND username was refused
 * permanently — the bank saved it, OpenWave refused it, and the app reported a
 * failure every time. That was the "username set in app still says failed to add
 * to OpenWave Identity" report, four fixes deep.
 *
 * The dangerous half is not the rename, it is the RELEASE. An npt handle is a
 * payment address: saved as a payee, printed on a QR, pasted into a message. If
 * a released handle were re-issued, every one of those still-circulating
 * references would quietly start paying a stranger — no error, and a plausible
 * name on the confirmation screen. So most of what follows is about the old name.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandleRenameTest {

    @Mock private lateinit var identityRepo: IdentityRepository
    @Mock private lateinit var linkedAccountRepo: LinkedAccountRepository
    @Mock private lateinit var bankRepo: BankRepository
    @Mock private lateinit var portalUserService: PortalUserService
    @Mock private lateinit var retiredHandleRepo: RetiredHandleRepository
    @Mock private lateinit var portalSecurityService: PortalSecurityService
    @Mock private lateinit var credentialNotificationService: PortalCredentialNotificationService

    @InjectMocks private lateinit var service: IdentityService

    private val nationalId = "123456789012"

    private fun identity(
        handle: String = "ahmed",
        nid: String? = "123456789012",
        status: IdentityStatus = IdentityStatus.ACTIVE,
        renamedAt: Instant? = null,
        renameCount: Int = 0
    ) = IdentityEntity(
        id = 7,
        nptHandle = handle,
        displayName = "Ahmed",
        status = status,
        nationalId = nid,
        handleRenamedAt = renamedAt,
        handleRenameCount = renameCount
    )

    @BeforeEach
    fun setUp() {
        `when`(bankRepo.findByBankHandle(anyString())).thenReturn(
            BankEntity(
                bankHandle = "andalus",
                displayName = "Andalus",
                country = "LY",
                coreUrl = "https://core.test",
                contactEmail = "ops@andalus.test",
                apiKeyHash = "hash"
            )
        )
        `when`(linkedAccountRepo.existsByIdentityIdAndBankHandle(anyLong(), anyString())).thenReturn(true)
        `when`(identityRepo.save(any(IdentityEntity::class.java))).thenAnswer { it.arguments[0] }
    }

    // ── the happy path ──────────────────────────────────────────────────

    @Test
    @DisplayName("a rename moves the handle and retires the old one")
    fun renameRetiresTheOldHandle() {
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle(anyString())).thenReturn(null)

        val result = service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)

        assertEquals("ahmed.ali", result.nptHandle)
        assertEquals(1, result.handleRenameCount)

        // THE POINT OF THE WHOLE FEATURE: the old name is reserved, not freed.
        val captor = org.mockito.ArgumentCaptor.forClass(RetiredHandleEntity::class.java)
        verify(retiredHandleRepo).save(captor.capture())
        assertEquals("ahmed", captor.value.handle)
        assertEquals("ahmed.ali", captor.value.replacedByHandle)
        assertEquals(7L, captor.value.formerIdentityId)
        assertEquals("andalus", captor.value.performedByBank)
    }

    @Test
    @DisplayName("the customer's portal login moves with the handle")
    fun portalLoginFollows() {
        // AuthController and CustomerPortalController both resolve the customer
        // with findByNptHandle(user.username), so a rename that leaves the login
        // behind locks them out of OpenWave entirely — worse than refusing.
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle(anyString())).thenReturn(null)

        service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)

        verify(portalUserService).renameCustomerUser("ahmed", "ahmed.ali")
    }

    @Test
    @DisplayName("renaming to the name you already hold is a no-op, not an error")
    fun renamingToSelfIsFree() {
        // The caller is often a retry. Burning a rename slot on one would punish
        // precisely the flaky-network case this feature exists for.
        val id = identity(renameCount = 0)
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)

        val result = service.renameHandle("ahmed", "AHMED", "andalus", nationalId)

        assertEquals("ahmed", result.nptHandle)
        assertEquals(0, result.handleRenameCount)
        verify(retiredHandleRepo, never()).save(any(RetiredHandleEntity::class.java))
        verify(portalUserService, never()).renameCustomerUser(anyString(), anyString())
    }

    // ── the old name is never released ──────────────────────────────────

    @Test
    @DisplayName("a retired handle cannot be claimed by anyone else")
    fun retiredHandleCannotBeClaimed() {
        // Without this the reservation is a comment. The first person to ask for
        // a renamed customer's old name would get it, and every payee, QR and
        // pasted address still pointing there would start paying them.
        `when`(retiredHandleRepo.findByHandle("ahmed")).thenReturn(
            RetiredHandleEntity(handle = "ahmed", formerIdentityId = 7)
        )

        assertThrows(HandleRetiredException::class.java) {
            service.claimHandle(
                nptHandle = "ahmed",
                bankHandle = "andalus",
                iban = "LY83027000000000000000001",
                displayName = "Someone Else",
                bankCustomerRef = "ref",
                setAsDefault = true,
                nationalId = "999999999999",
                phone = "0910000000",
                email = "someone@example.test"
            )
        }
    }

    @Test
    @DisplayName("a retired handle cannot be taken as a rename target either")
    fun retiredHandleCannotBeRenamedInto() {
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("old.name")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle("old.name")).thenReturn(
            RetiredHandleEntity(handle = "old.name", formerIdentityId = 99)
        )

        assertThrows(HandleRetiredException::class.java) {
            service.renameHandle("ahmed", "old.name", "andalus", nationalId)
        }
    }

    @Test
    @DisplayName("availability tells TAKEN, RETIRED and INVALID apart")
    fun availabilityDistinguishesTheThreeAnswers() {
        // Three different next actions: pick another, pick another and it will
        // never free up, fix the spelling. One sentence covering all three is
        // the defect this whole area has been producing.
        `when`(identityRepo.findByNptHandle("taken")).thenReturn(identity(handle = "taken"))
        `when`(identityRepo.findByNptHandle("gone")).thenReturn(null)
        `when`(retiredHandleRepo.existsByHandle("gone")).thenReturn(true)
        `when`(identityRepo.findByNptHandle("free")).thenReturn(null)
        `when`(retiredHandleRepo.existsByHandle("free")).thenReturn(false)

        assertEquals(HandleAvailability.TAKEN, service.handleAvailability("taken"))
        assertEquals(HandleAvailability.RETIRED, service.handleAvailability("gone"))
        assertEquals(HandleAvailability.AVAILABLE, service.handleAvailability("free"))
        assertEquals(HandleAvailability.INVALID, service.handleAvailability("A!"))
        assertEquals(HandleAvailability.INVALID, service.handleAvailability("ab"))
    }

    // ── who is allowed to do this ───────────────────────────────────────

    @Test
    @DisplayName("a bank that does not serve the customer cannot rename them")
    fun foreignBankCannotRename() {
        // Every bank authenticates the same way, so without this any registered
        // bank could rename any customer in the network.
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(linkedAccountRepo.existsByIdentityIdAndBankHandle(anyLong(), anyString())).thenReturn(false)

        assertThrows(HandleRenameNotPermittedException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "nub", nationalId)
        }
        verify(retiredHandleRepo, never()).save(any(RetiredHandleEntity::class.java))
    }

    @Test
    @DisplayName("a wrong national ID is refused even from the customer's own bank")
    fun wrongNationalIdIsRefused() {
        val id = identity(nid = "123456789012")
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)

        assertThrows(HandleRenameNotPermittedException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "andalus", "000000000000")
        }
    }

    @Test
    @DisplayName("an identity with no national ID cannot be renamed at all")
    fun missingNationalIdIsRefused() {
        // Otherwise a null on both sides would compare equal and wave the
        // rename through — the classic shape of an authorisation check that
        // passes hardest when it knows least.
        val id = identity(nid = null)
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)

        assertThrows(HandleRenameNotPermittedException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "andalus", "000000000000")
        }
    }

    @Test
    @DisplayName("an unregistered bank is refused")
    fun unknownBankIsRefused() {
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(bankRepo.findByBankHandle("ghost")).thenReturn(null)

        assertThrows(BankNotFoundException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "ghost", nationalId)
        }
    }

    @Test
    @DisplayName("a suspended identity cannot rename")
    fun inactiveIdentityCannotRename() {
        val id = identity(status = IdentityStatus.SUSPENDED)
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)

        assertThrows(HandleRenameNotPermittedException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)
        }
    }

    // ── squatting ───────────────────────────────────────────────────────

    @Test
    @DisplayName("renames are rate limited, because each one reserves a name forever")
    fun cooldownIsEnforced() {
        // Not about load. Every rename permanently removes a string from
        // circulation, so an unlimited rename is a squatting tool paid for by
        // the registry — and a way to probe which names are free by watching
        // which renames succeed.
        val id = identity(renamedAt = Instant.now().minus(2, ChronoUnit.DAYS))
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle(anyString())).thenReturn(null)

        assertThrows(HandleRenameTooSoonException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)
        }
        verify(retiredHandleRepo, never()).save(any(RetiredHandleEntity::class.java))
    }

    @Test
    @DisplayName("a rename long after the last one is allowed")
    fun cooldownExpires() {
        val id = identity(renamedAt = Instant.now().minus(400, ChronoUnit.DAYS), renameCount = 1)
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle(anyString())).thenReturn(null)

        val result = service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)
        assertEquals("ahmed.ali", result.nptHandle)
        assertEquals(2, result.handleRenameCount)
    }

    @Test
    @DisplayName("there is a lifetime cap as well as a cooldown")
    fun lifetimeCapIsEnforced() {
        // A cooldown alone still permits twelve a year, every one of them
        // removing a name from circulation permanently.
        val id = identity(renamedAt = Instant.now().minus(400, ChronoUnit.DAYS), renameCount = 3)
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("ahmed.ali")).thenReturn(null)
        `when`(retiredHandleRepo.findByHandle(anyString())).thenReturn(null)

        assertThrows(HandleRenameTooSoonException::class.java) {
            service.renameHandle("ahmed", "ahmed.ali", "andalus", nationalId)
        }
    }

    // ── ordinary refusals ───────────────────────────────────────────────

    @Test
    @DisplayName("a live holder of the new name wins")
    fun liveHolderWins() {
        val id = identity()
        `when`(identityRepo.findByNptHandle("ahmed")).thenReturn(id)
        `when`(identityRepo.findByNptHandle("salma")).thenReturn(identity(handle = "salma"))

        assertThrows(HandleTakenException::class.java) {
            service.renameHandle("ahmed", "salma", "andalus", nationalId)
        }
    }

    @Test
    @DisplayName("a malformed new name is rejected before anything is read")
    fun malformedNameIsRejected() {
        assertThrows(HandleInvalidFormatException::class.java) {
            service.renameHandle("ahmed", "A B!", "andalus", nationalId)
        }
        verify(identityRepo, never()).save(any(IdentityEntity::class.java))
    }

    @Test
    @DisplayName("renaming an identity that does not exist is a not-found")
    fun missingIdentityIsNotFound() {
        `when`(identityRepo.findByNptHandle("ghost")).thenReturn(null)

        assertThrows(IdentityNotFoundException::class.java) {
            service.renameHandle("ghost", "ahmed.ali", "andalus", nationalId)
        }
    }
}
