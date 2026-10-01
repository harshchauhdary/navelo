package app.navelo.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.navelo.shared.FilenameParser
import app.navelo.shared.ItemType
import app.navelo.shared.MediaItem
import app.navelo.shared.RootType
import app.navelo.shared.StableIds
import java.util.ArrayDeque
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MediaLibrary(
    context: Context,
    private val persistence: ServerPersistence,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private data class PublishedLibrary(
        val snapshot: LibrarySnapshot,
        val byId: Map<String, StoredMediaItem>,
    )
    @Volatile private var published = PublishedLibrary(
        LibrarySnapshot(0, emptyList(), emptyList()), emptyMap(),
    )
    private var current: LibrarySnapshot
        get() = published.snapshot
        set(value) { published = PublishedLibrary(value, value.items.associateBy { it.item.id }) }
    private val history = LinkedHashMap<Long, List<StoredMediaItem>>()

    suspend fun restore(): LibrarySnapshot = mutex.withLock {
        val saved = persistence.loadLibrary()
        current = LibrarySnapshot(saved.revision, saved.roots, saved.items)
        remember(current)
        current
    }

    suspend fun current(): LibrarySnapshot = current

    suspend fun snapshot(revision: Long): List<StoredMediaItem>? = synchronized(history) {
        history[revision]
    }

    suspend fun find(id: String): StoredMediaItem? = published.byId[id]

    suspend fun addRoot(uri: Uri, type: RootType): LibrarySnapshot = mutex.withLock {
        takeReadPermission(uri)
        val rootDocumentId = documentId(uri)
        val id = StableIds.forDocument(uri.authority.orEmpty(), rootDocumentId)
        val existing = current.roots.firstOrNull { it.id == id }
        val document = DocumentFile.fromTreeUri(appContext, uri)
        val name = document?.name?.takeIf(String::isNotBlank) ?: defaultRootName(type)
        val added = StoredRoot(
            id,
            name,
            type,
            uri.toString(),
            available = document?.exists() == true,
            itemCount = existing?.itemCount ?: 0,
        )
        val configChanged = existing == null || existing.name != added.name || existing.type != added.type ||
            existing.uri != added.uri
        val roots = if (existing == null) current.roots + added else current.roots.map {
            if (it.id == id) added.copy(itemCount = it.itemCount) else it
        }
        val base = current.copy(roots = roots)
        replaceAfterScan(base, roots, forceRevision = configChanged)
    }

    suspend fun removeRoot(id: String): LibrarySnapshot = mutex.withLock {
        val removed = current.roots.firstOrNull { it.id == id } ?: return@withLock current
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(removed.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val next = LibrarySnapshot(
            revision = current.revision + 1,
            roots = current.roots.filterNot { it.id == id },
            items = current.items.filterNot { it.item.rootId == id },
        )
        commit(next)
    }

    suspend fun rescan(): LibrarySnapshot = mutex.withLock {
        replaceAfterScan(current, current.roots, forceRevision = false)
    }

    private fun replaceAfterScan(
        base: LibrarySnapshot,
        roots: List<StoredRoot>,
        forceRevision: Boolean,
    ): LibrarySnapshot {
        val oldByRoot = base.items.groupBy { it.item.rootId }
        val scannedItems = ArrayList<StoredMediaItem>()
        val scannedRoots = ArrayList<StoredRoot>()
        roots.forEach { root ->
            val result = scanRoot(root)
            if (result == null) {
                scannedRoots += root.copy(available = false)
                scannedItems += oldByRoot[root.id].orEmpty()
            } else {
                scannedRoots += root.copy(
                    available = true,
                    itemCount = result.count { it.item.type == ItemType.VIDEO },
                )
                scannedItems += result
            }
        }
        val orderedRoots = scannedRoots.sortedWith { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
        val orderedItems = scannedItems.sortedWith { left, right ->
            left.item.relativePath.compareTo(right.item.relativePath, ignoreCase = true)
                .takeIf { it != 0 }
                ?: left.item.id.compareTo(right.item.id)
        }
        val publicChanged = orderedRoots.map(StoredRoot::publicValue) != current.roots.map(StoredRoot::publicValue) ||
            orderedItems.map(StoredMediaItem::item) != current.items.map(StoredMediaItem::item)
        val locatorChanged = orderedItems != current.items || orderedRoots != current.roots
        if (!forceRevision && !publicChanged) {
            if (locatorChanged) {
                current = current.copy(roots = orderedRoots, items = orderedItems)
                persistence.saveLibrary(current)
            }
            return current
        }
        return commit(
            LibrarySnapshot(current.revision + 1, orderedRoots, orderedItems),
        )
    }

    private fun scanRoot(root: StoredRoot): List<StoredMediaItem>? = try {
        val treeUri = Uri.parse(root.uri)
        val rootDocumentId = treeDocumentId(treeUri)
        data class Work(
            val documentId: String,
            val relativeParent: String,
            val parentId: String?,
        )
        val work = ArrayDeque<Work>()
        val seenDirectories = HashSet<String>()
        seenDirectories += rootDocumentId
        work.add(Work(rootDocumentId, "", null))
        val result = ArrayList<StoredMediaItem>()
        while (work.isNotEmpty()) {
            val node = work.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            val cursor = appContext.contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)
                ?: throw IllegalStateException("Storage provider returned no folder listing")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (it.moveToNext()) {
                    val childDocumentId = it.getString(idIndex) ?: continue
                    val filename = if (nameIndex >= 0 && !it.isNull(nameIndex)) {
                        it.getString(nameIndex)
                    } else {
                        childDocumentId.substringAfterLast('/')
                    }
                    if (filename.isNullOrBlank()) continue
                    val mimeType = if (mimeIndex >= 0 && !it.isNull(mimeIndex)) it.getString(mimeIndex) else null
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
                    val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                        it.getLong(sizeIndex).coerceAtLeast(0)
                    } else {
                        0
                    }
                    val modified = if (modifiedIndex >= 0 && !it.isNull(modifiedIndex)) {
                        it.getLong(modifiedIndex)
                    } else {
                        0
                    }
                    val id = StableIds.forDocument(root.id, childDocumentId)
                    val relativePath = if (node.relativeParent.isEmpty()) {
                        filename
                    } else {
                        "${node.relativeParent}/$filename"
                    }
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (seenDirectories.add(childDocumentId)) {
                            val item = MediaItem(
                                id = id,
                                filename = filename,
                                displayName = filename,
                                parentId = node.parentId,
                                rootId = root.id,
                                relativePath = relativePath,
                                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                                modifiedTime = modified,
                                type = ItemType.DIRECTORY,
                            )
                            result += StoredMediaItem(item, childUri.toString())
                            work.add(Work(childDocumentId, relativePath, id))
                        }
                        continue
                    }
                    val extension = filename.substringAfterLast('.', "").lowercase()
                    val itemType = when (extension) {
                        in VIDEO_EXTENSIONS -> ItemType.VIDEO
                        in SUBTITLE_EXTENSIONS -> ItemType.SUBTITLE
                        else -> continue
                    }
                    val parsed = FilenameParser.parse(filename, relativePath)
                    val item = MediaItem(
                        id = id,
                        filename = filename,
                        displayName = if (parsed.episode != null) "${parsed.title} · Episode ${parsed.episode}" else parsed.title,
                        parentId = node.parentId,
                        rootId = root.id,
                        relativePath = relativePath,
                        size = size,
                        mimeType = mimeType ?: fallbackMimeType(extension, itemType),
                        extension = extension,
                        modifiedTime = modified,
                        type = itemType,
                        titleHint = parsed.title,
                        year = parsed.year,
                        showHint = parsed.show,
                        season = parsed.season,
                        episode = parsed.episode,
                    )
                    result += StoredMediaItem(item, childUri.toString())
                }
            }
        }
        result
    } catch (_: Exception) {
        null
    }

    private fun commit(snapshot: LibrarySnapshot): LibrarySnapshot {
        current = snapshot
        remember(snapshot)
        persistence.saveLibrary(snapshot)
        return snapshot
    }

    private fun remember(snapshot: LibrarySnapshot) = synchronized(history) {
        history[snapshot.revision] = snapshot.items
        while (history.size > SNAPSHOT_HISTORY_SIZE) {
            history.remove(history.keys.first())
        }
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (failure: SecurityException) {
            val alreadyGranted = appContext.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!alreadyGranted) throw failure
        }
    }

    private fun documentId(uri: Uri): String = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.recoverCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrElse { uri.toString() }

    private fun treeDocumentId(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrElse { documentId(uri) }

    private fun defaultRootName(type: RootType) = when (type) {
        RootType.MOVIES -> "Movies"
        RootType.TV_SHOWS -> "TV Shows"
        RootType.OTHER -> "Other media"
    }

    private fun fallbackMimeType(extension: String, type: ItemType): String {
        if (type == ItemType.SUBTITLE) return when (extension) {
            "vtt" -> "text/vtt"
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            else -> "text/plain"
        }
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        const val SNAPSHOT_HISTORY_SIZE = 3
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val VIDEO_EXTENSIONS = setOf(
            "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg",
            "mts", "ogv", "ts", "webm", "wmv",
        )
        val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "ssa", "sub", "ttml", "vtt")
    }
}
