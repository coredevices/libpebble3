package io.rebble.libpebblecommon.voice

import kotlinx.coroutines.flow.Flow

interface TranscriptionProvider {
    suspend fun transcribe(encoderInfo: VoiceEncoderInfo, languageCode: String, audioFrames: Flow<UByteArray>): TranscriptionResult
    suspend fun canServeSession(): Boolean
}