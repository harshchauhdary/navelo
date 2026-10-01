package app.navelo.server

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.navelo.shared.Protocol
import app.navelo.shared.SecureStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

private val Context.naveloServerPreferences by preferencesDataStore(name = "navelo_server")

internal class ServerPersistence(context: Context) {
    private val appContext = context.applicationContext
    private val indexFile = AtomicFile(File(appContext.filesDir, "navelo-library-v1.json"))
    private val secureStore = SecureStore(appContext, "navelo_server")
    private val fallbackPreferences = appContext.getSharedPreferences("navelo_server_identity", Context.MODE_PRIVATE)

    fun loadServerName(): String? = fallbackPreferences.getString("display_name", null)

    fun saveServerName(name: String?) {
        check(fallbackPreferences.edit().apply {
            if (name == null) remove("display_name") else putString("display_name", name)
        }.commit()) { "Could not save the server name. Please try again." }
    }

    suspend fun loadOrCreateServerId(): String {
        val dataStoreId = runCatching {
            appContext.naveloServerPreferences.data.first()[SERVER_ID]?.takeIf(String::isNotBlank)
        }.getOrNull()
        val fallbackId = fallbackPreferences.getString("server_id", null)?.takeIf(String::isNotBlank)
        val id = dataStoreId ?: fallbackId ?: UUID.randomUUID().toString()
        check(fallbackPreferences.edit().putString("server_id", id).commit())
        runCatching { appContext.naveloServerPreferences.edit { it[SERVER_ID] = id } }
        return id
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadLibrary(): PersistedLibrary = runCatching {
        indexFile.openRead().use { input ->
            Protocol.json.decodeFromStream(PersistedLibrary.serializer(), input)
        }
    }.getOrDefault(PersistedLibrary())

    @OptIn(ExperimentalSerializationApi::class)
    fun saveLibrary(snapshot: LibrarySnapshot) {
        val output = indexFile.startWrite()
        try {
            Protocol.json.encodeToStream(
                PersistedLibrary.serializer(),
                PersistedLibrary(snapshot.revision, snapshot.roots, snapshot.items),
                output,
            )
            output.flush()
            indexFile.finishWrite(output)
        } catch (failure: Throwable) {
            indexFile.failWrite(output)
            throw failure
        }
    }

    fun loadTrusted(): List<TrustedRecord> {
        val encoded = secureStore.get(TRUSTED_DEVICES) ?: return emptyList()
        return runCatching {
            Protocol.json.decodeFromString<List<TrustedRecord>>(encoded)
        }.getOrDefault(emptyList())
    }

    fun saveTrusted(records: List<TrustedRecord>) {
        secureStore.put(TRUSTED_DEVICES, Protocol.json.encodeToString(records))
    }

    private companion object {
        val SERVER_ID = stringPreferencesKey("server_id")
        const val TRUSTED_DEVICES = "trusted_devices"
    }
}
