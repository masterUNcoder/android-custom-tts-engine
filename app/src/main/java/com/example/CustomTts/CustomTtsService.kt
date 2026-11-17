package com.example.CustomTts

import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import android.widget.Toast
import com.example.CustomTts.data.PrefKeys
import com.example.CustomTts.data.settingsDataStore
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CustomTtsService : TextToSpeechService() {
    companion object {
        private const val TAG = "DummyTtsService"
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "TTS: $msg", Toast.LENGTH_LONG).show()
        }
    }

    private data class WavHeaderInfo(
        val sampleRate: Int,
        val numChannels: Short,
        val bitsPerSample: Short,
        val audioFormatCode: Short,
        val androidEncoding: Int,
        val dataOffset: Int,
        val dataSize: Int
    )

    private fun parseWavHeader(wavBytes: ByteArray): WavHeaderInfo? {
        if (wavBytes.size < 44) return null
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val riffChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (riffChunkId != "RIFF") return null
            buffer.position(8)
            val format = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (format != "WAVE") return null
            buffer.position(12)
            val fmtChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (fmtChunkId != "fmt ") return null
            val fmtChunkSize = buffer.int
            val audioFormat = buffer.short
            val numChannels = buffer.short
            val sampleRate = buffer.int
            buffer.int
            buffer.short
            val bitsPerSample = buffer.short
            val androidEncoding = when {
                audioFormat == 1.toShort() && bitsPerSample == 16.toShort() -> AudioFormat.ENCODING_PCM_16BIT
                audioFormat == 3.toShort() && bitsPerSample == 32.toShort() -> AudioFormat.ENCODING_PCM_FLOAT
                else -> return null
            }
            buffer.position(20 + fmtChunkSize)
            var dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            while (dataChunkId != "data" && buffer.remaining() >= 8) {
                val chunkSize = buffer.int
                if (buffer.remaining() < chunkSize + 4) break
                buffer.position(buffer.position() + chunkSize)
                if (buffer.remaining() < 4) break
                dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            }
            if (dataChunkId != "data") return null
            val rawDataSize = buffer.int
            val dataOffset = buffer.position()
            val dataSize = if (rawDataSize <= 0) wavBytes.size - dataOffset else rawDataSize
            if (wavBytes.size < dataOffset + dataSize) return null
            return WavHeaderInfo(
                sampleRate = sampleRate,
                numChannels = numChannels,
                bitsPerSample = bitsPerSample,
                audioFormatCode = audioFormat,
                androidEncoding = androidEncoding,
                dataOffset = dataOffset,
                dataSize = dataSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WAV header", e)
            return null
        }
    }

    private lateinit var httpClient: HttpClient

    override fun onCreate() {
        super.onCreate()
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 60000
            }
        }
    }

    override fun onDestroy() {
        if (::httpClient.isInitialized) {
            httpClient.close()
        }
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("rus", "RUS", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val text = request.charSequenceText?.toString() ?: ""
        val androidRate = request.speechRate.toFloat().coerceIn(20f, 300f)
        val openAiSpeed = androidRate / 100.0f

        showToast("Запрос: ${text.take(25)}")

        if (text.isBlank()) {
            try {
                callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
            } catch (e: Exception) {
                Log.e(TAG, "Blank text error", e)
            }
            return
        }

        runBlocking {
            try {
                val currentSettings = applicationContext.settingsDataStore.data.first()
                val backendUrl = currentSettings[PrefKeys.BACKEND_URL] ?: ""
                val apiKey = currentSettings[PrefKeys.API_KEY] ?: ""
                val apiModel = currentSettings[PrefKeys.TTS_MODEL] ?: "tts-1"
                val apiVoice = currentSettings[PrefKeys.TTS_VOICE] ?: "alloy"
                val requestedAudioFormat = currentSettings[PrefKeys.RESPONSE_FORMAT] ?: "wav"

                if (backendUrl.isBlank()) {
                    showToast("Пустой URL в настройках!")
                    callback.error(TextToSpeech.ERROR_SERVICE)
                    return@runBlocking
                }

                val response: HttpResponse = httpClient.post(backendUrl) {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        TtsRequestPayload(
                            model = apiModel,
                            input = text,
                            voice = apiVoice,
                            response_format = requestedAudioFormat,
                            speed = openAiSpeed
                        )
                    )
                }

                if (response.status.isSuccess()) {
                    val audioBytes = response.readBytes()
                    if (audioBytes.isEmpty()) {
                        showToast("Сервер вернул пустой файл")
                        callback.error(TextToSpeech.ERROR_NETWORK)
                        return@runBlocking
                    }
                    val wavInfo = parseWavHeader(audioBytes)
                    if (wavInfo == null) {
                        showToast("Ошибка заголовка WAV (${audioBytes.size} байт)")
                        callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                        return@runBlocking
                    }
                    val startResult = callback.start(
                        wavInfo.sampleRate,
                        wavInfo.androidEncoding,
                        wavInfo.numChannels.toInt()
                    )
                    if (startResult == TextToSpeech.ERROR) {
                        showToast("Ошибка аудиовыхода Android")
                        callback.error(TextToSpeech.ERROR_OUTPUT)
                        return@runBlocking
                    }
                    val dataOffset = wavInfo.dataOffset
                    val dataSize = wavInfo.dataSize
                    val audioChunkSize = 8192
                    var bytesStreamed = 0
                    while (bytesStreamed < dataSize) {
                        val readOffset = dataOffset + bytesStreamed
                        if (readOffset >= audioBytes.size) break
                        val chunkSize = Math.min(dataSize - bytesStreamed, audioChunkSize)
                        val availableBytesInArray = audioBytes.size - readOffset
                        val actualChunkSize = Math.min(chunkSize, availableBytesInArray)
                        if (actualChunkSize <= 0) break
                        val res = callback.audioAvailable(audioBytes, readOffset, actualChunkSize)
                        if (res == TextToSpeech.ERROR) {
                            callback.error(TextToSpeech.ERROR_OUTPUT)
                            return@runBlocking
                        }
                        bytesStreamed += actualChunkSize
                    }
                    callback.done()
                } else {
                    val err = try { response.bodyAsText().take(80) } catch (_: Exception) { "" }
                    showToast("Ошибка сервера ${response.status.value}: $err")
                    callback.error(TextToSpeech.ERROR_NETWORK)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis error", e)
                showToast("Сбой: ${e.message?.take(60)}")
                callback.error(TextToSpeech.ERROR_NETWORK)
            }
        }
        @kotlinx.serialization.Serializable
data class TtsRequestPayload(
    val model: String,
    val input: String,
    val voice: String,
    val response_format: String = "wav",
    val speed: Float = 1.0f
)
