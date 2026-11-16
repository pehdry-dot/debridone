package com.debridone.accounts

import com.debridone.platform.AppPaths
import com.debridone.platform.db.OpenOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountsDatabaseTest {
    private lateinit var dir: Path
    private lateinit var paths: AppPaths
    private var now = 1_800_000_000_000L
    private val fastHasher = PasswordHasher(memoryKiB = 256, iterations = 1)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("debridone-accounts-test")
        paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun open() = AccountsDatabase.open(paths, passwordHasher = fastHasher, pinHasher = fastHasher, clock = { now })

    @Test
    fun passwordHashesAreSaltedAndVerifiable() {
        val hasher = PasswordHasher(memoryKiB = 1024, iterations = 2)
        val first = hasher.hash("correct horse".toCharArray())
        val second = hasher.hash("correct horse".toCharArray())
        assertNotEquals(first, second, "each hash must use a fresh salt")
        assertTrue(first.startsWith("argon2id\$v=19\$m=1024,t=2,p=1\$"))
        assertTrue(hasher.verify("correct horse".toCharArray(), first))
        assertFalse(hasher.verify("Correct horse".toCharArray(), first))
        assertFalse(hasher.verify("x".toCharArray(), "not-a-hash"))
        assertFalse(hasher.verify("x".toCharArray(), null))
        assertTrue(PasswordHasher(memoryKiB = 2048, iterations = 2).needsRehash(first))
        assertFalse(hasher.needsRehash(first))
    }

    @Test
    fun createAccountSignsInAndNeverStoresThePassword() {
        open().use { db ->
            val account = db.createAccount("Sam@Example.com", "hunter2-secret")
            assertEquals("Sam", account.displayName)
            assertTrue(account.hasPassword)
            assertFalse(account.isGuest)
            assertEquals(account.id, db.currentSession()?.accountId)
        }
        val raw = String(paths.accountsDir.resolve("accounts.db").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("hunter2-secret"), "the clear-text password must not be on disk")
        assertTrue(raw.contains("argon2id"))
    }

    @Test
    fun rejectsDuplicateLoginsCaseInsensitivelyAndWeakInput() {
        open().use { db ->
            db.createAccount("sam@example.com", "password1")
            assertEquals(AccountError.LOGIN_TAKEN, assertFailsWith<AccountException> { db.createAccount("SAM@EXAMPLE.COM", "password2") }.error)
            assertEquals(AccountError.WEAK_PASSWORD, assertFailsWith<AccountException> { db.createAccount("other", "12345") }.error)
            assertEquals(AccountError.INVALID_LOGIN, assertFailsWith<AccountException> { db.createAccount("a b", "password1") }.error)
            assertEquals(AccountError.INVALID_LOGIN, assertFailsWith<AccountException> { db.createAccount("ab", "password1") }.error)
            assertEquals(1, db.listAccounts().size)
        }
    }

    @Test
    fun signInChecksPasswordAndLocksAfterRepeatedFailures() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "right-password")
            db.signOut()
            assertNull(db.currentSession())

            assertEquals(AccountError.INVALID_CREDENTIALS, assertFailsWith<AccountException> { db.signIn("nobody@example.com", "x") }.error)
            repeat(5) {
                assertEquals(AccountError.INVALID_CREDENTIALS, assertFailsWith<AccountException> { db.signIn("sam@example.com", "wrong") }.error)
            }
            // Locked now: even the right password is refused until the lock expires.
            val locked = assertFailsWith<AccountException> { db.signIn("sam@example.com", "right-password") }
            assertEquals(AccountError.TOO_MANY_ATTEMPTS, locked.error)
            assertEquals(30, locked.retryAfterSeconds)
            assertNull(db.currentSession())

            now += 31_000
            assertEquals(account.id, db.signIn("SAM@example.com", "right-password").id)
            assertEquals(account.id, db.currentSession()?.accountId)
        }
    }

    @Test
    fun changePasswordRequiresTheCurrentOne() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "first-password")
            assertFailsWith<AccountException> { db.changePassword(account.id, "wrong", "second-password") }
            db.changePassword(account.id, "first-password", "second-password")
            db.signOut()
            assertFailsWith<AccountException> { db.signIn("sam@example.com", "first-password") }
            assertEquals(account.id, db.signIn("sam@example.com", "second-password").id)
        }
    }

    @Test
    fun signOutKeepsAccountAndProfiles() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val profile = db.createProfile(account.id, ProfileDraft(null, "Sam"))
            db.signOut()
            assertNull(db.currentSession())
            assertEquals(listOf(profile.id), db.listProfiles(account.id).map { it.id })
            db.signIn("sam@example.com", "password1")
            assertEquals(listOf("Sam"), db.listProfiles(account.id).map { it.name })
        }
    }

    @Test
    fun guestAccountIsReusedAndCanBeUpgraded() {
        open().use { db ->
            val guest = db.signInGuest()
            assertTrue(guest.isGuest)
            assertFalse(guest.hasPassword)
            val profile = db.createProfile(guest.id, ProfileDraft(null, "Me"))
            db.signOut()
            assertEquals(guest.id, db.signInGuest().id, "the same guest account must come back")

            val upgraded = db.upgradeGuest(guest.id, "me@example.com", "password1")
            assertFalse(upgraded.isGuest)
            assertTrue(upgraded.hasPassword)
            db.signOut()
            assertEquals(guest.id, db.signIn("me@example.com", "password1").id)
            assertEquals(listOf(profile.id), db.listProfiles(guest.id).map { it.id })
        }
    }

    @Test
    fun profileIdsAreUniqueAcrossAccountsAndNeverReused() {
        open().use { db ->
            val a = db.createAccount("a@example.com", "password1")
            val b = db.createAccount("b@example.com", "password1")
            val a1 = db.createProfile(a.id, ProfileDraft(null, "A1"))
            val b1 = db.createProfile(b.id, ProfileDraft(null, "B1"))
            val a2 = db.createProfile(a.id, ProfileDraft(null, "A2"))
            assertEquals(AccountsDatabase.FIRST_PROFILE_ID, a1.id)
            assertEquals(3, setOf(a1.id, b1.id, a2.id).size)

            assertTrue(db.deleteProfile(a.id, a2.id))
            val a3 = db.createProfile(a.id, ProfileDraft(null, "A3"))
            assertTrue(a3.id > a2.id, "a deleted profile's id must not be handed out again")
            assertFalse(db.deleteProfile(b.id, a1.id), "an account cannot delete another account's profile")
            assertEquals(listOf("A1", "A3"), db.listProfiles(a.id).map { it.name })
            assertEquals(listOf("B1"), db.listProfiles(b.id).map { it.name })
        }
    }

    @Test
    fun enforcesProfileLimitAndValidation() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            repeat(AccountsDatabase.MAX_PROFILES_PER_ACCOUNT) { db.createProfile(account.id, ProfileDraft(null, "P$it")) }
            assertEquals(AccountError.PROFILE_LIMIT_REACHED, assertFailsWith<AccountException> { db.createProfile(account.id, ProfileDraft(null, "extra")) }.error)
            assertEquals(AccountError.INVALID_PROFILE, assertFailsWith<AccountException> {
                db.updateProfile(account.id, ProfileDraft(db.listProfiles(account.id).first().id, "   "))
            }.error)
        }
    }

    @Test
    fun updateRenamesAndChangesAvatar() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val profile = db.createProfile(account.id, ProfileDraft(null, "Sam", avatarColorHex = "#e53935"))
            assertEquals("#E53935", profile.avatarColorHex)
            val updated = db.updateProfile(account.id, ProfileDraft(profile.id, "Samantha", "#43A047", avatarId = "fox", avatarUrl = "file:///tmp/a.png"))
            assertEquals("Samantha", updated.name)
            assertEquals("#43A047", updated.avatarColorHex)
            assertEquals("fox", updated.avatarId)
            assertEquals("file:///tmp/a.png", updated.avatarUrl)
        }
    }

    @Test
    fun defaultAndActiveProfileSelection() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val one = db.createProfile(account.id, ProfileDraft(null, "One"))
            val two = db.createProfile(account.id, ProfileDraft(null, "Two"))
            db.setDefaultProfile(account.id, two.id)
            assertEquals(listOf(false, true), db.listProfiles(account.id).map { it.isDefault })
            db.setDefaultProfile(account.id, one.id)
            assertEquals(listOf(true, false), db.listProfiles(account.id).map { it.isDefault })

            assertEquals(false, db.currentSession()?.hasEverSelectedProfile)
            db.setActiveProfile(account.id, two.id)
            db.setRememberLastProfile(account.id, true)
            val session = assertNotNull(db.currentSession())
            assertEquals(two.id, session.activeProfileId)
            assertTrue(session.hasEverSelectedProfile)
            assertTrue(session.rememberLastProfile)

            db.deleteProfile(account.id, two.id)
            assertNull(db.currentSession()?.activeProfileId, "a deleted profile cannot stay active")
        }
    }

    @Test
    fun replaceProfilesCreatesUpdatesAndDeletesAtomically() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val other = db.createAccount("other@example.com", "password1")
            val foreign = db.createProfile(other.id, ProfileDraft(null, "Foreign"))
            val keep = db.createProfile(account.id, ProfileDraft(null, "Keep"))
            val drop = db.createProfile(account.id, ProfileDraft(null, "Drop"))
            db.setPin(keep.id, "1234", null)
            val reserved = db.reserveProfileId()

            val removed = db.replaceProfiles(
                account.id,
                listOf(ProfileDraft(keep.id, "Kept"), ProfileDraft(reserved, "Brand new")),
            )
            assertEquals(listOf(drop.id), removed)
            val profiles = db.listProfiles(account.id)
            assertEquals(listOf("Kept", "Brand new"), profiles.map { it.name })
            assertEquals(listOf(keep.id, reserved), profiles.map { it.id })
            assertTrue(profiles.first().pinEnabled, "replacing profiles must not drop an existing PIN")

            // Trying to adopt another account's profile fails and changes nothing.
            assertFailsWith<AccountException> {
                db.replaceProfiles(account.id, listOf(ProfileDraft(keep.id, "Changed"), ProfileDraft(foreign.id, "Stolen")))
            }
            assertEquals(listOf("Kept", "Brand new"), db.listProfiles(account.id).map { it.name })
            assertEquals(listOf("Foreign"), db.listProfiles(other.id).map { it.name })
        }
    }

    @Test
    fun saveProfilesNeverDeletesProfilesMissingFromTheList() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val one = db.createProfile(account.id, ProfileDraft(null, "One"))
            val two = db.createProfile(account.id, ProfileDraft(null, "Two"))
            val fresh = db.reserveProfileId()

            // An incomplete (even empty) list must not remove anything.
            db.saveProfiles(account.id, emptyList())
            db.saveProfiles(account.id, listOf(ProfileDraft(two.id, "Two renamed"), ProfileDraft(fresh, "Three")))

            assertEquals(listOf("One", "Two renamed", "Three"), db.listProfiles(account.id).map { it.name })
            assertEquals(listOf(one.id, two.id, fresh), db.listProfiles(account.id).map { it.id })
        }
    }

    @Test
    fun pinLifecycleWithLockout() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val profile = db.createProfile(account.id, ProfileDraft(null, "Kid"))
            assertEquals(PinFailure.NOT_SET, db.verifyPin(profile.id, "1234").reason)
            assertEquals(PinFailure.INVALID_FORMAT, db.setPin(profile.id, "12a4", null).reason)
            assertTrue(db.setPin(profile.id, "1234", null).unlocked)
            assertTrue(assertNotNull(db.findProfile(profile.id)).pinEnabled)
            assertTrue(db.verifyPin(profile.id, "1234").unlocked)

            // Changing needs the current PIN.
            assertFalse(db.setPin(profile.id, "9999", "0000").unlocked)
            assertTrue(db.setPin(profile.id, "9999", "1234").unlocked)

            repeat(4) { assertEquals(PinFailure.INCORRECT, db.verifyPin(profile.id, "0000").reason) }
            val fifth = db.verifyPin(profile.id, "0000")
            assertEquals(PinFailure.INCORRECT, fifth.reason)
            assertEquals(60, fifth.retryAfterSeconds)
            val locked = db.verifyPin(profile.id, "9999")
            assertEquals(PinFailure.LOCKED, locked.reason)
            assertFalse(locked.unlocked)
            now += 61_000
            assertTrue(db.verifyPin(profile.id, "9999").unlocked)

            assertFalse(db.clearPinWithAccountPassword(account.id, profile.id, "wrong-password"))
            assertTrue(db.clearPinWithAccountPassword(account.id, profile.id, "password1"))
            assertFalse(assertNotNull(db.findProfile(profile.id)).pinEnabled)
        }
    }

    @Test
    fun settingsAreScopedAndRemovedWithTheirProfile() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val one = db.createProfile(account.id, ProfileDraft(null, "One"))
            val two = db.createProfile(account.id, ProfileDraft(null, "Two"))
            db.setSetting(SettingsScope.Global, "shell.last_module", "music")
            db.setSetting(SettingsScope.OfProfile(one.id), "appearance.theme", "amber")
            db.setSetting(SettingsScope.OfProfile(two.id), "appearance.theme", "ocean")
            db.setSetting(SettingsScope.OfAccount(account.id), "sync.enabled", "false")

            assertEquals("amber", db.getSetting(SettingsScope.OfProfile(one.id), "appearance.theme"))
            assertEquals("ocean", db.getSetting(SettingsScope.OfProfile(two.id), "appearance.theme"))
            assertNull(db.getSetting(SettingsScope.Global, "appearance.theme"))

            db.deleteProfile(account.id, one.id)
            assertNull(db.getSetting(SettingsScope.OfProfile(one.id), "appearance.theme"))
            assertEquals("ocean", db.getSetting(SettingsScope.OfProfile(two.id), "appearance.theme"))
            assertEquals("music", db.getSetting(SettingsScope.Global, "shell.last_module"))
        }
    }

    @Test
    fun deleteAccountNeedsPasswordAndRemovesEverythingItOwned() {
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1")
            val other = db.createAccount("other@example.com", "password1")
            val profile = db.createProfile(account.id, ProfileDraft(null, "Sam"))
            val otherProfile = db.createProfile(other.id, ProfileDraft(null, "Other"))
            db.setSetting(SettingsScope.OfProfile(profile.id), "k", "v")
            db.setSetting(SettingsScope.OfProfile(otherProfile.id), "k", "v")

            assertFailsWith<AccountException> { db.deleteAccount(account.id, "wrong") }
            assertEquals(listOf(profile.id), db.deleteAccount(account.id, "password1"))

            assertNull(db.findAccount(account.id))
            assertNull(db.findProfile(profile.id))
            assertNull(db.getSetting(SettingsScope.OfProfile(profile.id), "k"))
            assertEquals("v", db.getSetting(SettingsScope.OfProfile(otherProfile.id), "k"))
            assertEquals(listOf("Other"), db.listProfiles(other.id).map { it.name })
        }
    }

    /** Acceptance test: two profiles with different settings survive a full close and reopen. */
    @Test
    fun accountsProfilesSessionAndPreferencesSurviveRestart() {
        val accountId: String
        val firstId: Long
        val secondId: Long
        open().use { db ->
            val account = db.createAccount("sam@example.com", "password1", displayName = "Sam's Mac")
            accountId = account.id
            firstId = db.createProfile(account.id, ProfileDraft(null, "Sam", "#E53935", avatarId = "fox")).id
            secondId = db.createProfile(account.id, ProfileDraft(null, "Alex", "#43A047", avatarUrl = "file:///avatars/alex.png")).id
            db.setSetting(SettingsScope.OfProfile(firstId), "appearance.theme", "amber")
            db.setSetting(SettingsScope.OfProfile(secondId), "appearance.theme", "ocean")
            db.setPin(secondId, "4321", null)
            db.setDefaultProfile(account.id, firstId)
            db.setActiveProfile(account.id, secondId)
            db.setRememberLastProfile(account.id, true)
        }

        // "Close the application completely. Reopen the application."
        val reopened = open()
        reopened.use { db ->
            assertEquals(OpenOutcome.OPENED, reopened.openOutcome)
            val session = assertNotNull(db.currentSession(), "the sign-in must be remembered")
            assertEquals(accountId, session.accountId)
            assertEquals(secondId, session.activeProfileId)
            assertTrue(session.rememberLastProfile)
            assertEquals("Sam's Mac", db.findAccount(accountId)?.displayName)

            val profiles = db.listProfiles(accountId)
            assertEquals(listOf("Sam", "Alex"), profiles.map { it.name })
            assertEquals(listOf("#E53935", "#43A047"), profiles.map { it.avatarColorHex })
            assertEquals("fox", profiles[0].avatarId)
            assertEquals("file:///avatars/alex.png", profiles[1].avatarUrl)
            assertEquals(listOf(true, false), profiles.map { it.isDefault })
            assertEquals(listOf(false, true), profiles.map { it.pinEnabled })
            assertTrue(db.verifyPin(secondId, "4321").unlocked)
            assertEquals("amber", db.getSetting(SettingsScope.OfProfile(firstId), "appearance.theme"))
            assertEquals("ocean", db.getSetting(SettingsScope.OfProfile(secondId), "appearance.theme"))
        }
    }

    @Test
    fun damagedAccountsDatabaseIsRestoredFromBackup() {
        val accountId: String
        open().use { db ->
            accountId = db.createAccount("sam@example.com", "password1").id
            db.createProfile(accountId, ProfileDraft(null, "Sam"))
            db.backupNow()
        }
        val file = paths.accountsDir.resolve("accounts.db")
        listOf("-wal", "-shm").forEach { Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + it)) }
        file.writeBytes(ByteArray(16384) { 0x33 })

        val recovered = open()
        recovered.use { db ->
            assertEquals(OpenOutcome.RESTORED_FROM_BACKUP, recovered.openOutcome)
            assertEquals(listOf("Sam"), db.listProfiles(accountId).map { it.name })
            assertEquals(accountId, db.signIn("sam@example.com", "password1").id)
        }
    }
}
