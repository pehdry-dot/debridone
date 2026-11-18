package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import com.debridone.cloud.CloudTestEnvironment.Companion.PROFILE_A
import com.debridone.cloud.CloudTestEnvironment.Companion.PROFILE_B
import com.debridone.cloud.testing.StandInRealDebrid
import com.debridone.cloud.testing.StandInTorBox
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connecting, keeping and sharing the user's Real-Debrid and TorBox accounts, against local
 * servers that answer as the two services' documentation describes. No test contacts a real
 * service.
 */
class CloudAccountsTest {
    private val env = CloudTestEnvironment()

    @AfterTest
    fun tearDown() = env.close()

    private fun assertNoSecretOnDisk(why: String) = env.secretTexts().forEach { assertFalse(env.dataFolderContains(it), "$why: $it must be in the credential store only") }

    @Test
    fun bothAccountsConnectSideBySideAndTheirCredentialsStayInTheSecretStore() {
        val cloud = env.open()
        assertEquals(listOf(ProviderId.REAL_DEBRID, ProviderId.TORBOX), cloud.accounts.providers.map { it.id })

        // Real-Debrid: a code to type on its own page. Until it is allowed, nothing is connected or stored.
        val pending = cloud.accounts.beginSignIn(ProviderId.REAL_DEBRID)
        assertEquals(StandInRealDebrid.USER_CODE, pending.userCode)
        assertEquals("https://real-debrid.com/device", pending.verificationUrl)
        assertNull(cloud.accounts.finishSignIn(pending, PROFILE_A, shared = false))
        assertEquals(emptyList(), cloud.accounts.accounts.value)
        env.realDebrid.approved = true
        val realDebrid = assertNotNull(cloud.accounts.finishSignIn(pending, PROFILE_A, shared = false))
        assertEquals(StandInRealDebrid.USERNAME, realDebrid.username)
        assertEquals("listener@example.org", realDebrid.email)
        assertEquals("Premium", realDebrid.plan)
        assertTrue(realDebrid.premium)
        assertEquals(java.time.Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), realDebrid.premiumUntil)
        assertEquals("code", realDebrid.authKind)
        assertEquals(AccountStatus.CONNECTED, realDebrid.status)

        // TorBox: its API key. Both stay connected at the same time.
        val torBox = env.connectTorBox(cloud)
        assertEquals(StandInTorBox.EMAIL, torBox.email)
        assertEquals("Pro", torBox.plan)
        assertTrue(torBox.premium)
        assertEquals("Yes", torBox.details["Renews automatically"])
        assertEquals("key", torBox.authKind)
        assertEquals(setOf(ProviderId.REAL_DEBRID, ProviderId.TORBOX), cloud.accounts.visibleTo(PROFILE_A).map { it.provider }.toSet())

        // What the providers handed over is in the credential store, and only there.
        val stored = assertNotNull(env.storedSecret(realDebrid.id))
        assertTrue(stored.contains(env.realDebrid.accessToken!!) && stored.contains(env.realDebrid.refreshToken!!) && stored.contains(StandInRealDebrid.CLIENT_SECRET))
        assertEquals(StandInTorBox.API_KEY, env.storedSecret(torBox.id))
        assertNoSecretOnDisk("after connecting")
        assertFalse(realDebrid.toString().contains("ACCESS") || torBox.toString().contains(StandInTorBox.API_KEY))

        // A restart: both are still connected, and still answer.
        val reopened = env.open()
        assertEquals(listOf(realDebrid.id, torBox.id), reopened.accounts.visibleTo(PROFILE_A).map { it.id })
        assertEquals("Premium", reopened.accounts.refresh(PROFILE_A, realDebrid.id).plan)
        assertEquals("Pro", reopened.accounts.refresh(PROFILE_A, torBox.id).plan)

        // Disconnecting one erases its credentials and leaves the other alone.
        reopened.disconnect(PROFILE_A, realDebrid.id)
        assertNull(env.storedSecret(realDebrid.id))
        assertEquals(listOf(torBox.id), reopened.accounts.visibleTo(PROFILE_A).map { it.id })
        assertNotNull(env.storedSecret(torBox.id))
    }

    @Test
    fun aKeyThatIsWrongOrMalformedIsRefusedAndLeavesNothing() {
        val cloud = env.open()
        val wrong = assertFailsWith<CloudException> { cloud.accounts.connectWithKey(ProviderId.TORBOX, "not-the-right-key-0123456789", PROFILE_A, shared = false) }
        assertEquals(Reason.AUTH_EXPIRED, wrong.reason)
        assertEquals("TorBox does not accept this API key.", wrong.message)
        assertTrue(assertFailsWith<CloudException> { cloud.accounts.connectWithKey(ProviderId.TORBOX, "short", PROFILE_A, shared = false) }.message!!.contains("does not look like"))
        val wrongToken = assertFailsWith<CloudException> { cloud.accounts.connectWithKey(ProviderId.REAL_DEBRID, "NOTTHETOKEN0123456789ABCDEFGHIJKLMNOP", PROFILE_A, shared = false) }
        assertEquals("Real-Debrid does not accept this API token.", wrongToken.message)
        assertEquals(emptyList(), cloud.accounts.accounts.value, "a refused key leaves nothing behind")

        // The private API token is the other way in to Real-Debrid; it is never renewed.
        val account = cloud.accounts.connectWithKey(ProviderId.REAL_DEBRID, "  ${StandInRealDebrid.PRIVATE_TOKEN}\n", PROFILE_A, shared = false)
        assertEquals("key", account.authKind)
        assertTrue(env.storedSecret(account.id)!!.contains(StandInRealDebrid.PRIVATE_TOKEN))
        cloud.accounts.refresh(PROFILE_A, account.id)
        assertFalse(env.realDebrid.requests.any { it.startsWith("POST /oauth") })
        assertNoSecretOnDisk("with an API token")

        // TorBox has no sign-in with a code.
        assertEquals(Reason.UNSUPPORTED, assertFailsWith<CloudException> { cloud.accounts.beginSignIn(ProviderId.TORBOX) }.reason)
    }

    @Test
    fun anAccountBelongsToOneProfileOrIsSharedWithAll() {
        val cloud = env.open()
        val mine = env.connectRealDebrid(cloud, PROFILE_A, shared = false)
        val shared = env.connectTorBox(cloud, PROFILE_A, shared = true)
        assertFalse(mine.shared)
        assertTrue(shared.shared)

        // The other profile sees the shared account only, and cannot use the first one in any way.
        assertEquals(listOf(shared.id), cloud.accounts.visibleTo(PROFILE_B).map { it.id })
        assertNull(cloud.accounts.account(PROFILE_B, mine.id))
        for (attempt in listOf<() -> Any?>(
            { cloud.accounts.refresh(PROFILE_B, mine.id) },
            { cloud.files.refresh(PROFILE_B, mine.id) },
            { cloud.accounts.setShared(PROFILE_B, mine.id, true) },
            { cloud.disconnect(PROFILE_B, mine.id) },
        )) {
            assertEquals(Reason.NOT_ALLOWED, assertFailsWith<CloudException> { attempt() }.reason)
        }
        cloud.files.refresh(PROFILE_A, mine.id)
        cloud.files.refresh(PROFILE_B, shared.id)
        assertTrue(cloud.files.items(PROFILE_A).any { it.provider == ProviderId.REAL_DEBRID })
        assertTrue(cloud.files.items(PROFILE_B).none { it.provider == ProviderId.REAL_DEBRID }, "another profile is shown nothing of it")
        assertTrue(cloud.files.items(PROFILE_B).any { it.provider == ProviderId.TORBOX })
        val file = cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MUSIC)).first { it.provider == ProviderId.REAL_DEBRID }
        assertNull(cloud.files.file(PROFILE_B, file.id))
        assertEquals(Reason.NOT_FOUND, assertFailsWith<CloudException> { cloud.files.resolve(PROFILE_B, file.id) }.reason)
        assertEquals(Reason.NOT_ALLOWED, assertFailsWith<CloudException> { cloud.files.resolve(PROFILE_B, mine.id, file.kind, file.itemRemoteId, file.ref) }.reason)
        assertEquals(1, cloud.downloads.enqueue(PROFILE_B, listOf(file.id)).problems.size, "and it cannot download from it")

        // The same Real-Debrid account can be connected by the other profile for itself.
        env.realDebrid.approved = true
        val theirs = env.connectRealDebrid(cloud, PROFILE_B, shared = false)
        assertTrue(theirs.id != mine.id)
        cloud.disconnect(PROFILE_B, theirs.id)

        // Sharing it makes it everyone's; keeping it again makes it the asking profile's.
        cloud.accounts.setShared(PROFILE_A, mine.id, true)
        assertEquals(setOf(mine.id, shared.id), cloud.accounts.visibleTo(PROFILE_B).map { it.id }.toSet())
        cloud.accounts.setShared(PROFILE_B, mine.id, false)
        assertEquals(listOf(shared.id), cloud.accounts.visibleTo(PROFILE_A).map { it.id })

        // Deleting a profile takes the accounts that were its own, with their credentials; shared ones stay.
        cloud.deleteProfile(PROFILE_B)
        assertNull(env.storedSecret(mine.id))
        assertEquals(listOf(shared.id), cloud.accounts.accounts.value.map { it.id })
        assertNotNull(env.storedSecret(shared.id))
    }

    @Test
    fun theSameAccountIsNotConnectedTwiceForOneProfile() {
        val cloud = env.open()
        env.connectTorBox(cloud, PROFILE_A, shared = true)
        val again = assertFailsWith<CloudException> { env.connectTorBox(cloud, PROFILE_B, shared = false) }
        assertTrue(again.message!!.contains("already connected for all profiles"), again.message)
        assertEquals(1, cloud.accounts.accounts.value.size)
    }

    @Test
    fun anExpiringSignInIsRenewedAndAWithdrawnOneAsksToSignInAgain() {
        env.realDebrid.tokenLifetimeSeconds = 30
        val cloud = env.open()
        val account = env.connectRealDebrid(cloud)
        assertEquals("ACCESS-1-SECRET", env.realDebrid.accessToken)

        // About to expire: renewed before it is used, with the refresh token, and the new one is kept.
        env.realDebrid.tokenLifetimeSeconds = 3_600
        cloud.accounts.refresh(PROFILE_A, account.id)
        assertEquals("ACCESS-2-SECRET", env.realDebrid.accessToken)
        val stored = env.storedSecret(account.id)!!
        assertTrue(stored.contains("ACCESS-2-SECRET") && stored.contains("REFRESH-2-SECRET") && !stored.contains("ACCESS-1-SECRET"))
        assertEquals("REFRESH-1-SECRET", env.realDebrid.forms.last { it["grant_type"] != null }["code"])

        // Good for an hour now: no further renewal, also after a restart.
        val reopened = env.open()
        reopened.files.refresh(PROFILE_A, account.id)
        assertEquals(2, env.realDebrid.requests.count { it == "POST /oauth/v2/token" })
        assertNoSecretOnDisk("after renewing")

        // The user withdraws DebridOne's rights on Real-Debrid: said in words, and nothing local is lost.
        val before = reopened.files.items(PROFILE_A).size
        env.realDebrid.revoked = true
        val refused = assertFailsWith<CloudException> { reopened.accounts.refresh(PROFILE_A, account.id) }
        assertEquals(Reason.AUTH_EXPIRED, refused.reason)
        assertTrue(refused.message!!.contains("Sign in again"))
        env.secretTexts().forEach { assertFalse(refused.message!!.contains(it)) }
        val marked = reopened.accounts.account(PROFILE_A, account.id)!!
        assertEquals(AccountStatus.NEEDS_SIGN_IN, marked.status)
        assertEquals(refused.message, marked.lastError)
        assertEquals(Reason.AUTH_EXPIRED, assertFailsWith<CloudException> { reopened.files.refresh(PROFILE_A, account.id) }.reason, "also when it is noticed while listing")
        assertEquals(before, reopened.files.items(PROFILE_A).size, "what was listed stays listed")

        // Signing in again renews the same account in place.
        env.realDebrid.revoked = false
        val renewed = env.connectRealDebrid(reopened)
        assertEquals(account.id, renewed.id)
        assertEquals(AccountStatus.CONNECTED, renewed.status)
        assertEquals(1, reopened.accounts.accounts.value.size)
        reopened.files.refresh(PROFILE_A, account.id)

        // TorBox: a replaced key is reported the same way.
        val torBox = env.connectTorBox(reopened)
        env.torBox.keyReplaced = true
        val replaced = assertFailsWith<CloudException> { reopened.accounts.refresh(PROFILE_A, torBox.id) }
        assertEquals(Reason.AUTH_EXPIRED, replaced.reason)
        assertFalse(replaced.message!!.contains(StandInTorBox.API_KEY))
        assertEquals(AccountStatus.NEEDS_SIGN_IN, reopened.accounts.account(PROFILE_A, torBox.id)!!.status)
    }

    @Test
    fun testServersAreOnlyEverOnThisComputer() {
        fun endpoints(vararg values: Pair<String, String>) = CloudEndpoints.fromEnvironment { name -> values.toMap()[name] }
        assertEquals(CloudEndpoints(), endpoints())
        assertEquals("http://127.0.0.1:8791", endpoints("DEBRIDONE_REAL_DEBRID_API" to "http://127.0.0.1:8791/").realDebrid)
        assertEquals("http://localhost:9000", endpoints("DEBRIDONE_TORBOX_API" to "http://localhost:9000").torBox)
        // Anything that is not this computer is ignored: the variables cannot send a sign-in elsewhere.
        for (elsewhere in listOf("https://evil.example.org", "http://127.0.0.1.evil.example.org:80", "http://10.0.0.5:8080", "http://127.0.0.1:8791/path", "http://user@127.0.0.1:8791")) {
            assertEquals(CloudEndpoints(), endpoints("DEBRIDONE_REAL_DEBRID_API" to elsewhere, "DEBRIDONE_TORBOX_API" to elsewhere), elsewhere)
        }
        assertFalse(RealDebridClient().isTestServer || TorBoxClient().isTestServer)
        assertTrue(RealDebridClient(env.realDebrid.origin).isTestServer)
    }
}
