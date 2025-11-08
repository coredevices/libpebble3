package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.ObjectType
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.coroutines.cancellation.CancellationException

class LanguagePackInstaller(
    private val putBytesSession: PutBytesSession,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : ConnectedPebble.Language {
    private val logger = Logger.withTag("LanguagePackInstaller")

    override suspend fun installLanguagePack(path: Path): Boolean =
        withContext(connectionCoroutineScope.coroutineContext) {
            logger.d { "installLanguagePack() $path" }
            val metadata = SystemFileSystem.metadataOrNull(path)
            if (metadata == null) {
                logger.e { "Failed to get metadata for $path" }
                return@withContext false
            }
            val source = SystemFileSystem.source(path).buffered()
            try {
                val installFlow = putBytesSession.beginSession(
                    size = metadata.size.toUInt(),
                    type = ObjectType.FILE,
                    bank = 0u,
                    filename = "lang",
                    source = source,
                    sendInstall = true,
                )
                installFlow.collect { state ->
                    when (state) {
                        is PutBytesSession.SessionState.Finished -> logger.d { "installLanguagePack finished" }
                        is PutBytesSession.SessionState.Open -> logger.d { "installLanguagePack opened" }
                        is PutBytesSession.SessionState.Sending -> logger.d { "installLanguagePack sending: ${state.totalSent}" }
                    }
                }
                logger.d { "installLanguagePack finished" }
                return@withContext true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Error installing language pack" }
                return@withContext false
            } finally {
                source.close()
            }
        }
}
