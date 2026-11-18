package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What reading an account anew found. */
data class RefreshSummary(val entries: Int, val files: Int, val removed: Int)

/**
 * What is in the connected accounts, as last seen: the browser reads this cache, and [refresh]
 * brings it up to date from the provider.
 *
 * A refresh that fails part of the way leaves everything that was there: entries are only
 * forgotten after a complete listing of their kind that no longer contained them. The files of a
 * finished transfer are asked for once and remembered until the transfer changes.
 *
 * Every read and every action names the profile it is for, and sees only that profile's accounts.
 */
class CloudFileRepository internal constructor(
    private val store: CloudStore,
    private val accounts: DebridAccountManager,
    private val clock: () -> Long,
) {
    private val _revision = MutableStateFlow(0L)

    /** Changes whenever the cached listings changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<Long>>(emptySet())

    /** The accounts being read right now. */
    val refreshing: StateFlow<Set<Long>> = _refreshing.asStateFlow()

    private val locks = ConcurrentHashMap<Long, Any>()

    private fun visible(profileId: Long?): List<Long> = accounts.visibleTo(profileId).map { it.id }

    // ---------------------------------------------------------------- reading the cache

    fun items(profileId: Long?, query: CloudQuery = CloudQuery()): List<CloudItemRow> = store.items(visible(profileId), query)

    fun item(profileId: Long?, itemId: Long): CloudItemRow? = store.item(itemId)?.takeIf { it.accountId in visible(profileId) }

    fun files(profileId: Long?, itemId: Long): List<CloudFileRow> = if (item(profileId, itemId) == null) emptyList() else store.files(itemId)

    fun file(profileId: Long?, fileId: Long): CloudFileRow? = store.file(fileId)?.takeIf { it.accountId in visible(profileId) }

    /** Every finished file on the given shelves (or with the given extensions): what a module is offered. */
    fun filesOf(profileId: Long?, classes: Set<MediaClass>, extensions: Set<String> = emptySet(), accountId: Long? = null): List<CloudFileRow> =
        store.filesOf(visible(profileId).filter { accountId == null || it == accountId }, classes, extensions)

    // ---------------------------------------------------------------- reading the provider

    /**
     * Reads one account again. [fresh] asks the provider to skip its own cache (TorBox keeps its
     * lists for up to ten minutes). Throws [CloudException]; what was cached stays as it was.
     */
    fun refresh(profileId: Long?, accountId: Long, fresh: Boolean = false): RefreshSummary = synchronized(locks.computeIfAbsent(accountId) { Any() }) {
        _refreshing.update { it + accountId }
        try {
            val summary = accounts.use(profileId, accountId) { provider, credentials ->
                var entries = 0
                var removed = 0
                for (kind in provider.kinds) {
                    val seenAt = clock()
                    val known = store.knownItems(accountId, kind)
                    val listed = provider.list(credentials, kind, fresh)
                    for (item in listed) {
                        if (item.remoteId.isEmpty()) continue
                        entries++
                        val previous = known[item.remoteId]
                        val rowId = store.upsertItem(accountId, item, seenAt)
                        val inline = item.files
                        when {
                            inline != null -> store.setFiles(rowId, accountId, inline, "listed", item.signature)
                            // Already asked, and nothing about it changed since.
                            previous != null && previous.filesState != "none" && previous.signature == item.signature -> Unit
                            else -> {
                                val files = try {
                                    provider.files(credentials, item)
                                } catch (e: CloudException) {
                                    // Removed at the provider between the two requests: the next listing will not have it.
                                    if (e.reason == Reason.NOT_FOUND) null else throw e
                                }
                                if (files != null) store.setFiles(rowId, accountId, files, if (files.any { it.opaque }) "packed" else "listed", item.signature)
                            }
                        }
                    }
                    // Only now, after a complete listing of this kind, is anything forgotten.
                    removed += store.removeUnseen(accountId, kind, seenAt)
                    _revision.update { it + 1 }
                }
                store.hideDuplicates(accountId)
                store.markListed(accountId, clock())
                RefreshSummary(entries, store.filesOf(listOf(accountId), MediaClass.entries.toSet()).size, removed)
            }
            accounts.publish()
            summary
        } catch (e: CloudException) {
            if (e.reason != Reason.AUTH_EXPIRED && e.reason != Reason.NOT_ALLOWED) {
                store.setAccountStatus(accountId, AccountStatus.ERROR, e.message, clock())
                accounts.publish()
            }
            throw e
        } finally {
            _refreshing.update { it - accountId }
            _revision.update { it + 1 }
        }
    }

    /** Reads every account the profile may use; one account failing does not stop the others. */
    fun refreshAll(profileId: Long?, fresh: Boolean = false): Map<Long, Result<RefreshSummary>> =
        accounts.visibleTo(profileId).associate { account -> account.id to runCatching { refresh(profileId, account.id, fresh) } }

    // ---------------------------------------------------------------- download addresses

    /** A download address for a cached file, made now and handed to the caller only. */
    fun resolve(profileId: Long?, fileId: Long, fresh: Boolean = false): ResolvedLink {
        val row = file(profileId, fileId) ?: throw CloudException(Reason.NOT_FOUND, "This file is no longer listed in your account. Refresh the list.")
        return resolve(profileId, row.accountId, row.kind, row.itemRemoteId, row.ref, fresh)
    }

    fun resolve(profileId: Long?, accountId: Long, kind: ItemKind, itemRemoteId: String, ref: String, fresh: Boolean = false): ResolvedLink =
        accounts.use(profileId, accountId) { provider, credentials -> provider.resolve(credentials, kind, itemRemoteId, ref, fresh) }

    // ---------------------------------------------------------------- removing from the cloud

    /** True when the provider lets this entry be removed from the account through its API. */
    fun canDeleteFromCloud(item: CloudItemRow): Boolean = item.kind in accounts.provider(item.provider).deletableKinds

    /**
     * Removes an entry from the user's account at the provider, permanently. This is never a side
     * effect of anything else: it is called only for the explicit, confirmed "Delete from
     * <provider>" action. Local copies are not touched.
     */
    fun deleteFromCloud(profileId: Long?, itemId: Long) {
        val row = item(profileId, itemId) ?: throw CloudException(Reason.NOT_FOUND, "This entry is no longer listed in your account.")
        if (!canDeleteFromCloud(row)) throw CloudException(Reason.UNSUPPORTED, "${row.provider.displayName} does not allow removing this kind of entry from here.")
        try {
            accounts.use(profileId, row.accountId) { provider, credentials -> provider.delete(credentials, row.kind, row.remoteId) }
        } catch (e: CloudException) {
            // Already gone at the provider: the outcome the user asked for.
            if (e.reason != Reason.NOT_FOUND) throw e
        }
        store.removeItemRow(row.id)
        store.hideDuplicates(row.accountId)
        _revision.update { it + 1 }
    }

    internal fun changed() = _revision.update { it + 1 }
}
