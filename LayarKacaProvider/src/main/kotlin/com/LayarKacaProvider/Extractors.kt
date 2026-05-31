package com.layarKacaProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


class Co4nxtrl : Filesim() {
    override val mainUrl = "https://co4nxtrl.com"
    override val name = "Co4nxtrl"
    override val requiresReferer = true
}


open class Hownetwork : ExtractorApi() {
    override val name = "Hownetwork"
    override val mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("id=")
        val response = app.post(
            "$mainUrl/api2.php?id=$id",
            data = mapOf(
                "r" to "",
                "d" to mainUrl,
            ),
            referer = url,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text
        val json = JSONObject(response)
        val file = json.optString("file")
        callback.invoke(newExtractorLink(
            this.name,
            this.name,
            file,
            type = INFER_TYPE,
            {
                this.referer = file
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Sec-GPC" to "1",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Priority" to "u=0",
                    "Pragma" to "no-cache",
                    "Cache-Control" to "no-cache",
                    "TE" to "trailers"
                )
            }
        ))
    }
}

class Cloudhownetwork : Hownetwork() {
    override var mainUrl = "https://cloud.hownetwork.xyz"
}

class Furher : Filesim() {
    override val name = "Furher"
    override var mainUrl = "https://furher.in"
}

class Furher2 : Filesim() {
    override val name = "Furher 2"
    override var mainUrl = "723qrh1p.fun"
}

class Turbovidhls : Filesim() {
    override val name = "Turbovidhls"
    override var mainUrl = "https://turbovidhls.com"
}

class ByseSayeveum : ByseBase() {
    override var name = "Byse"
    override var mainUrl = "https://f16px.com"
}

open class ByseBase : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val refererUrl = getBaseUrl(url)
        val playbackRoot = getPlayback(url) ?: return
        val streamUrl = decryptPlayback(playbackRoot.playback) ?: return

        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            mainUrl,
            headers = mapOf("Referer" to refererUrl),
        ).forEach(callback)
    }

    private suspend fun getPlayback(pageUrl: String): PlaybackRoot? {
        val details = getDetails(pageUrl) ?: return null
        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val code = getCodeFromUrl(embedFrameUrl)
        val playbackUrl = "$embedBase/api/videos/$code/embed/playback"
        val headers =
            mapOf(
                "accept" to "*/*",
                "accept-language" to "en-US,en;q=0.5",
                "referer" to embedFrameUrl,
                "x-embed-parent" to pageUrl,
            )
        return app.get(playbackUrl, headers = headers).parsedSafe<PlaybackRoot>()
    }

    private suspend fun getDetails(pageUrl: String): DetailsRoot? {
        val base = getBaseUrl(pageUrl)
        val code = getCodeFromUrl(pageUrl)
        return app.get("$base/api/videos/$code/embed/details").parsedSafe<DetailsRoot>()
    }

    private fun decryptPlayback(playback: Playback): String? {
        val keyBytes = buildAesKey(playback)
        val ivBytes = b64UrlDecode(playback.iv)
        val cipherBytes = b64UrlDecode(playback.payload)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, ivBytes),
        )

        var json = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (json.startsWith("\uFEFF")) json = json.substring(1)
        return tryParseJson<PlaybackDecrypt>(json)?.sources?.firstOrNull()?.url
    }

    private fun buildAesKey(playback: Playback): ByteArray {
        return b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
    }

    private fun b64UrlDecode(value: String): ByteArray {
        val fixed = value.replace('-', '+').replace('_', '/')
        val padding = (4 - fixed.length % 4) % 4
        return base64DecodeArray(fixed + "=".repeat(padding))
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun getCodeFromUrl(url: String): String {
        return URI(url).path.trimEnd('/').substringAfterLast('/')
    }
}

data class DetailsRoot(
    val id: Long,
    val code: String,
    @JsonProperty("embed_frame_url")
    val embedFrameUrl: String,
)

data class PlaybackRoot(
    val playback: Playback,
)

data class Playback(
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts")
    val keyParts: List<String>,
)

data class PlaybackDecrypt(
    val sources: List<PlaybackDecryptSource>? = null,
)

data class PlaybackDecryptSource(
    val url: String,
)
