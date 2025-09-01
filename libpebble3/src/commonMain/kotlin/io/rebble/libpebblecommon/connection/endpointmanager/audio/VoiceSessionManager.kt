package io.rebble.libpebblecommon.connection.endpointmanager.audio

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AudioStream
import io.rebble.libpebblecommon.packets.DictationResult
import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.Sentence
import io.rebble.libpebblecommon.packets.SessionSetupResult
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.packets.VoiceAttribute
import io.rebble.libpebblecommon.packets.VoiceAttributeType
import io.rebble.libpebblecommon.services.AudioStreamService
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.toProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class VoiceSessionManager(
    private val voiceService: VoiceService,
    private val audioStreamService: AudioStreamService,
    private val watchScope: ConnectionCoroutineScope,
    private val transcriptionProvider: TranscriptionProvider,
    private val watchConfig: WatchConfigFlow
) {
    companion object Companion {
        private val logger = Logger.withTag("VoiceSession")
    }
    private val _currentSession = MutableStateFlow<CurrentSession?>(null)
    val currentSession = _currentSession.asStateFlow()

    data class CurrentSession (
        val request: VoiceService.SessionSetupRequest,
        val result: CompletableDeferred<TranscriptionResult>
    )

    private fun makeSetupResult(
        sessionType: SessionType,
        result: Result,
        appInitiated: Boolean
    ): SessionSetupResult {
        val setupResult = SessionSetupResult(sessionType, result)
        if (appInitiated) {
            setupResult.flags.set(1u) // Indicates app-initiated session
        }
        return setupResult
    }

    private fun makeDictationResult(
        sessionId: UShort,
        result: Result,
        words: Iterable<TranscriptionWord>?,
        appUuid: Uuid
    ): DictationResult {
        return DictationResult(
            sessionId,
            result,
            buildList {
                words?.let {
                    add(VoiceAttribute(
                        id = VoiceAttributeType.Transcription.value,
                        content = VoiceAttribute.Transcription(
                            sentences = listOf(
                                Sentence(words.map { it.toProtocol() })
                            )
                        )
                    ))
                }
                if (appUuid != Uuid.NIL) {
                    add(VoiceAttribute(
                        id = VoiceAttributeType.AppUuid.value,
                        content = VoiceAttribute.AppUuid().apply {
                            uuid.set(appUuid)
                        }
                    ))
                }
            }
        ).apply {
            if (appUuid != Uuid.NIL) {
                flags.set(1u) // Indicates app-initiated session
            }
        }
    }

    fun init() {
        watchScope.launch {
            voiceService.sessionSetupRequests.flowOn(Dispatchers.IO).collectLatest { setupRequest ->
                logger.i { "New voice session started: $setupRequest" }
                var audioFrameFlowCollected = false
                val audioFrameFlow = audioStreamService.dataFlowForSession(setupRequest.sessionId.toUShort())
                    .transform { transfer ->
                        transfer.frames
                            .map { frame -> frame.data.get() }
                            .forEach { emit(it) }
                    }
                    .onStart {
                        audioFrameFlowCollected = true
                    }
                val appInitiated = setupRequest.appUuid != Uuid.NIL
                if (setupRequest.encoderInfo == null) {
                    logger.e { "Received voice session setup request without encoder info, cannot handle voice session." }
                    voiceService.send(makeSetupResult(
                        sessionType = setupRequest.sessionType,
                        result = Result.FailInvalidMessage,
                        appInitiated = appInitiated
                    ))
                    return@collectLatest
                }
                val language = watchConfig.value.speechRecognitionLanguage
                if (language != null && transcriptionProvider.canServeSession()) {
                    voiceService.send(makeSetupResult(
                        sessionType = setupRequest.sessionType,
                        result = Result.Success,
                        appInitiated = appInitiated
                    ))
                } else {
                    logger.w { "Voice session requested, but speech recognition is disabled or not available" }
                    voiceService.send(makeSetupResult(
                        sessionType = setupRequest.sessionType,
                        result = Result.FailDisabled,
                        appInitiated = appInitiated
                    ))
                    return@collectLatest
                }

                val resultCompletable = CompletableDeferred<TranscriptionResult>()
                _currentSession.value = CurrentSession(setupRequest, resultCompletable)
                logger.i { "Voice session initialized with ID: ${setupRequest.sessionId}" }
                val result = try {
                    transcriptionProvider.transcribe(setupRequest.encoderInfo, language, audioFrameFlow)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "Error during transcription: ${e.message}" }
                    TranscriptionResult.Error("Transcription error: ${e.message}")
                }
                logger.i { "Voice session completed with result: ${result::class.simpleName}" }
                if (!audioFrameFlowCollected) {
                    logger.w { "Audio frames not collected, sending audio stop packet" }
                    audioStreamService.send(AudioStream.StopTransfer(setupRequest.sessionId.toUShort()))
                }
                voiceService.send(
                    makeDictationResult(
                        sessionId = setupRequest.sessionId.toUShort(),
                        result = result.toProtocol(),
                        words = (result as? TranscriptionResult.Success)?.words,
                        appUuid = setupRequest.appUuid
                    )
                )
                resultCompletable.complete(result)
                _currentSession.value = null
            }
        }
    }
}