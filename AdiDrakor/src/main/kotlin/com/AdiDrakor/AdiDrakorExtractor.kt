package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.AdiDrakor.AdiDrakor.Companion.idlixAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.RequestBodyTypes
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import org.json.JSONObject 

object AdiDrakorExtractor : AdiDrakor() {

    // ================== IDLIX SOURCE (UPDATED) ==================
    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        try {
            val response = app.get(url)
            val document = response.document
            val directUrl = getBaseUrl(response.url)

            // 1. Extract Nonce and Time (New Logic)
            val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val script = document.select("script:containsData(window.idlix)").toString()
            val match = scriptRegex.find(script)
            val idlixNonce = match?.groups?.get(1)?.value ?: ""
            val idlixTime = match?.groups?.get(2)?.value ?: ""

            // 2. Iterate Servers
            document.select("ul#playeroptionsul > li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
                // 3. POST Request
                val json = app.post(
                    url = "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type,
                        "_n" to idlixNonce,
                        "_p" to id,
                        "_t" to idlixTime
                    ),
                    referer = url,
                    headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ResponseHash>() ?: return@amap

                // 4. Decrypt Logic
                val metrix = parseJson<AesData>(json.embed_url).m
                val password = createIdlixKey(json.key, metrix)
                val decrypted = AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)
                    ?.fixUrlBloat() ?: return@amap

                // 5. Load Extractor
                when {
                    decrypted.contains("jeniusplay", true) -> {
                        val finalUrl = if (decrypted.startsWith("//")) "https:$decrypted" else decrypted
                        Jeniusplay().getUrl(finalUrl, "$directUrl/", subtitleCallback, callback)
                    }
                    !decrypted.contains("youtube") -> {
                        loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                    }
                    else -> return@amap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createIdlixKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) {
            return ""
        }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) {
                    n += "\\x" + rList[index]
                }
            } catch (_: Exception) {
            }
        }
        return n
    }

    // ================== ADIMOVIEBOX SOURCE (FIXED) ==================
    suspend fun invokeAdimoviebox(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://filmboom.top" // API Baru
        val searchUrl = "$apiUrl/wefeed-h5-bff/web/subject/search"
        
        val searchBody = mapOf(
            "keyword" to title,
            "page" to "1",
            "perPage" to "0",
            "subjectType" to "0"
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val searchRes = app.post(searchUrl, requestBody = searchBody).text
        // Menggunakan AdimovieboxResponse dari Parser
        val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return
        
        val matchedMedia = items.find { item ->
            val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            (item.title.equals(title, true)) || 
            (item.title?.contains(title, true) == true && itemYear == year)
        } ?: return

        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath
        val se = if (season == null) 0 else season
        val ep = if (episode == null) 0 else episode
        
        val playUrl = "$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$se&ep=$ep"
        val validReferer = "$apiUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"

        val playRes = app.get(playUrl, referer = validReferer).text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return

        streams.reversed().distinctBy { it.url }.forEach { source ->
             callback.invoke(
                newExtractorLink(
                    "Adimoviebox",
                    "Adimoviebox",
                    source.url ?: return@forEach,
                    INFER_TYPE 
                ) {
                    this.referer = "$apiUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null) {
            val subUrl = "$apiUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.lanName ?: "Unknown",
                        sub.url ?: return@forEach
                    )
                )
            }
        }
    }
    
    // ================== GOMOVIES ==================
    suspend fun invokeGomovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeGpress(
            title,
            year,
            season,
            episode,
            callback,
            gomoviesAPI,
            "Gomovies",
            base64Decode("X3NtUWFtQlFzRVRi"),
            base64Decode("X3NCV2NxYlRCTWFU"),
        )
    }

    private suspend fun invokeGpress(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        api: String,
        name: String,
        mediaSelector: String,
        episodeSelector: String,
    ) {
        fun String.decrypt(key: String): List<GpressSources>? {
            return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key))
        }

        val slug = getEpisodeSlug(season, episode)
        val query = if (season == null) {
            title
        } else {
            "$title Season $season"
        }

        var cookies = mapOf(
            "_identitygomovies7" to """5a436499900c81529e3740fd01c275b29d7e2fdbded7d760806877edb1f473e0a%3A2%3A%7Bi%3A0%3Bs%3A18%3A%22_identitygomovies7%22%3Bi%3A1%3Bs%3A52%3A%22%5B2800906%2C%22L2aGGTL9aqxksKR0pLvL66TunKNe1xXb%22%2C2592000%5D%22%3B%7D""",
        )

        var res = app.get("$api/search/$query", cookies = cookies)
        cookies = gomoviesCookies ?: res.cookies.filter { it.key == "advanced-frontendgomovies7" }
            .also { gomoviesCookies = it }
        val doc = res.document
        val media = doc.select("div.$mediaSelector").map {
            Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href"))
        }.let { el ->
            if (el.size == 1) {
                el.firstOrNull()
            } else {
                el.find {
                    if (season == null) {
                        (it.first.equals(title, true) || it.first.equals(
                            "$title ($year)",
                            true
                        )) && it.second.equals("$year")
                    } else {
                        it.first.equals("$title - Season $season", true)
                    }
                }
            } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") }
        } ?: return

        val iframe = if (season == null) {
            media.third
        } else {
            app.get(
                fixUrl(
                    media.third,
                    api
                )
            ).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")
                ?.attr("href")
        } ?: return

        res = app.get(fixUrl(iframe, api), cookies = cookies)
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val qualities = intArrayOf(2160, 1440, 1080, 720, 480, 360)

        val (serverId, episodeId) = if (season == null) {
            url.substringAfterLast("/") to "0"
        } else {
            url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/")
                .substringBefore("-")
        }
        val serverRes = app.get(
            "$api/user/servers/$serverId?ep=$episodeId",
            cookies = cookies,
            headers = headers
        )
        val script = getAndUnpack(serverRes.text)
        val key = """key\s*="\s*(\d+)"""".toRegex().find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get(
                "$url?server=$server&_=$unixTimeMS",
                cookies = cookies,
                referer = url,
                headers = headers
            ).text
            val links = encryptedData.decrypt(key)
            links?.forEach { video ->
                qualities.filter { it <= video.max.toInt() }.forEach {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            video.src.split("360", limit = 3).joinToString(it.toString()),
                            ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$api/"
                            this.quality = it
                        }
                    )
                }
            }
        }

    }

    suspend fun invokeVidsrccc(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        val url = if (season == null) {
            "${AdiDrakor.vidsrcccAPI}/v2/embed/movie/$tmdbId"
        } else {
            "${AdiDrakor.vidsrcccAPI}/v2/embed/tv/$tmdbId/$season/$episode"
        }

        val script =
            app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return

        val userId = script.substringAfter("userId = \"").substringBefore("\";")
        val v = script.substringAfter("v = \"").substringBefore("\";")

        val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")

        val serverUrl = if (season == null) {
            "${AdiDrakor.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId"
        } else {
            "${AdiDrakor.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        }

        app.get(serverUrl).parsedSafe<VidsrcccResponse>()?.data?.amap {
            val sources =
                app.get("${AdiDrakor.vidsrcccAPI}/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data
                    ?: return@amap

            when {
                it.name.equals("VidPlay") -> {

                    callback.invoke(
                        newExtractorLink(
                            "VidPlay",
                            "VidPlay",
                            sources.source ?: return@amap,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "${AdiDrakor.vidsrcccAPI}/"
                        }
                    )

                    sources.subtitles?.map {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                it.label ?: return@map,
                                it.file ?: return@map
                            )
                        )
                    }
                }

                it.name.equals("UpCloud") -> {
                    val scriptData = app.get(
                        sources.source ?: return@amap,
                        referer = "${AdiDrakor.vidsrcccAPI}/"
                    ).document.selectFirst("script:containsData(source =)")?.data()
                    val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(
                        scriptData ?: return@amap
                    )?.groupValues?.get(1)?.fixUrlBloat()

                    val iframeRes =
                        app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text

                    val id = iframe.substringAfterLast("/").substringBefore("?")
                    val key = Regex("\\w{48}").find(iframeRes)?.groupValues?.get(0) ?: return@amap

                    app.get(
                        "${iframe.substringBeforeLast("/")}/getSources?id=$id&_k=$key",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        referer = iframe
                    ).parsedSafe<UpcloudResult>()?.sources?.amap file@{ source ->
                        callback.invoke(
                            newExtractorLink(
                                "UpCloud",
                                "UpCloud",
                                source.file ?: return@file,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "${AdiDrakor.vidsrcccAPI}/"
                            }
                        )
                    }

                }

                else -> {
                    return@amap
                }
            }
        }

    }

    suspend fun invokeVidsrc(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://cloudnestra.com"
        val url = if (season == null) {
            "${AdiDrakor.vidSrcAPI}/embed/movie?imdb=$imdbId"
        } else {
            "${AdiDrakor.vidSrcAPI}/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        }

        app.get(url).document.select(".serversList .server").amap { server ->
            when {
                server.text().equals("CloudStream Pro", ignoreCase = true) -> {
                    val hash =
                        app.get("$api/rcp/${server.attr("data-hash")}").text.substringAfter("/prorcp/")
                            .substringBefore("'")
                    val res = app.get("$api/prorcp/$hash").text
                    val m3u8Link = Regex("https:.*\\.m3u8").find(res)?.value

                    callback.invoke(
                        newExtractorLink(
                            "Vidsrc",
                            "Vidsrc",
                            m3u8Link ?: return@amap,
                            ExtractorLinkType.M3U8
                        )
                    )
                }

                server.text().equals("2Embed", ignoreCase = true) -> {
                    return@amap
                }

                server.text().equals("Superembed", ignoreCase = true) -> {
                    return@amap
                }

                else -> {
                    return@amap
                }
            }
        }

    }

    suspend fun invokeXprime(
        tmdbId: Int?,
        title: String? = null,
        year: Int? = null,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val servers = listOf("rage", "primebox")
        val serverName = servers.map { it.capitalize() }
        val referer = "https://xprime.tv/"
        runAllAsync(
            {
                val url = if (season == null) {
                    "${AdiDrakor.xprimeAPI}/${servers.first()}?id=$tmdbId"
                } else {
                    "${AdiDrakor.xprimeAPI}/${servers.first()}?id=$tmdbId&season=$season&episode=$episode"
                }

                val source = app.get(url).parsedSafe<RageSources>()?.url

                callback.invoke(
                    newExtractorLink(
                        serverName.first(),
                        serverName.first(),
                        source ?: return@runAllAsync,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                    }
                )
            },
            {
                val url = if (season == null) {
                    "${AdiDrakor.xprimeAPI}/${servers.last()}?name=$title&fallback_year=$year"
                } else {
                    "${AdiDrakor.xprimeAPI}/${servers.last()}?name=$title&fallback_year=$year&season=$season&episode=$episode"
                }

                val sources = app.get(url).parsedSafe<PrimeboxSources>()

                sources?.streams?.map { source ->
                    callback.invoke(
                        newExtractorLink(
                            serverName.last(),
                            serverName.last(),
                            source.value,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = getQualityFromName(source.key)
                        }
                    )
                }

                sources?.subtitles?.map { subtitle ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            subtitle.label ?: "",
                            subtitle.file ?: return@map
                        )
                    )
                }

            }
        )
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "${AdiDrakor.watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "${AdiDrakor.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "${AdiDrakor.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.label?.substringBefore("&nbsp")?.trim() ?: "",
                    fixUrl(sub.url ?: return@map null, AdiDrakor.watchSomuchAPI)
                )
            )
        }

    }

    suspend fun invokeMapple(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaType = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "${AdiDrakor.mappleAPI}/watch/$mediaType/$tmdbId"
        } else {
            "${AdiDrakor.mappleAPI}/watch/$mediaType/$season-$episode/$tmdbId"
        }

        val data = if (season == null) {
            """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]"""
        } else {
            """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"$season-$episode","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]"""
        }

        val headers = mapOf(
            "Next-Action" to "403f7ef15810cd565978d2ac5b7815bb0ff20258a5",
        )

        val res = app.post(
            url,
            requestBody = data.toRequestBody(RequestBodyTypes.TEXT.toMediaTypeOrNull()),
            headers = headers
        ).text
        val videoLink =
            tryParseJson<MappleSources>(res.substringAfter("1:").trim())?.data?.stream_url

        callback.invoke(
            newExtractorLink(
                "Mapple",
                "Mapple",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "${AdiDrakor.mappleAPI}/"
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

        val subRes = app.get(
            "${AdiDrakor.mappleAPI}/api/subtitles?id=$tmdbId&mediaType=$mediaType${if (season == null) "" else "&season=1&episode=1"}",
            referer = "${AdiDrakor.mappleAPI}/"
        ).text
        tryParseJson<ArrayList<MappleSubtitle>>(subRes)?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.display ?: "",
                    fixUrl(subtitle.url ?: return@map, AdiDrakor.mappleAPI)
                )
            )
        }

    }

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "${AdiDrakor.vidlinkAPI}/$type/$tmdbId"
        } else {
            "${AdiDrakor.vidlinkAPI}/$type/$tmdbId/$season/$episode"
        }

        val videoLink = app.get(
            url, interceptor = WebViewResolver(
                Regex("""${AdiDrakor.vidlinkAPI}/api/b/$type/A{32}"""), timeout = 15_000L
            )
        ).parsedSafe<VidlinkSources>()?.stream?.playlist

        callback.invoke(
            newExtractorLink(
                "Vidlink",
                "Vidlink",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "${AdiDrakor.vidlinkAPI}/"
            }
        )

    }

    suspend fun invokeVidfast(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val module = "hezushon/1000076901076321/0b0ce221/cfe60245-021f-5d4d-bacb-0d469f83378f/uva/jeditawev/b0535941d898ebdb81f575b2cfd123f5d18c6464/y/APA91zAOxU2psY2_BvBqEmmjG6QvCoLjgoaI-xuoLxBYghvzgKAu-HtHNeQmwxNbHNpoVnCuX10eEes1lnTcI2l_lQApUiwfx2pza36CZB34X7VY0OCyNXtlq-bGVCkLslfNksi1k3B667BJycQ67wxc1OnfCc5PDPrF0BA8aZRyMXZ3-2yxVGp"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "${AdiDrakor.vidfastAPI}/$type/$tmdbId"
        } else {
            "${AdiDrakor.vidfastAPI}/$type/$tmdbId/$season/$episode"
        }

        val res = app.get(
            url, interceptor = WebViewResolver(
                Regex("""${AdiDrakor.vidfastAPI}/$module/JEwECseLZdY"""),
                timeout = 15_000L
            )
        ).text

        tryParseJson<ArrayList<VidFastServers>>(res)?.filter { it.description?.contains("Original audio") == true }
            ?.amapIndexed { index, server ->
                val source =
                    app.get("${AdiDrakor.vidfastAPI}/$module/Sdoi/${server.data}", referer = "${AdiDrakor.vidfastAPI}/")
                        .parsedSafe<VidFastSources>()

                callback.invoke(
                    newExtractorLink(
                        "Vidfast",
                        "Vidfast [${server.name}]",
                        source?.url ?: return@amapIndexed,
                        INFER_TYPE
                    )
                )

                if (index == 1) {
                    source.tracks?.map { subtitle ->
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                subtitle.label ?: return@map,
                                subtitle.file ?: return@map
                            )
                        )
                    }
                }

            }

    }

    suspend fun invokeWyzie(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "${AdiDrakor.wyzieAPI}/search?id=$tmdbId"
        } else {
            "${AdiDrakor.wyzieAPI}/search?id=$tmdbId&season=$season&episode=$episode"
        }

        val res = app.get(url).text

        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.display ?: return@map,
                    subtitle.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeVixsrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val proxy = "https://proxy.heistotron.uk"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "${AdiDrakor.vixsrcAPI}/$type/$tmdbId"
        } else {
            "${AdiDrakor.vixsrcAPI}/$type/$tmdbId/$season/$episode"
        }

        val res =
            app.get(url).document.selectFirst("script:containsData(window.masterPlaylist)")?.data()
                ?: return

        val video1 =
            Regex("""'token':\s*'(\w+)'[\S\s]+'expires':\s*'(\w+)'[\S\s]+url:\s*'(\S+)'""").find(res)
                ?.let {
                    val (token, expires, path) = it.destructured
                    "$path?token=$token&expires=$expires&h=1&lang=en"
                } ?: return

        val video2 =
            "$proxy/p/${base64Encode("$proxy/api/proxy/m3u8?url=${encode(video1)}&source=sakura|ananananananananaBatman!".toByteArray())}"

        listOf(
            VixsrcSource("Vixsrc [Alpha]", video1, url),
            VixsrcSource("Vixsrc [Beta]", video2, "${AdiDrakor.mappleAPI}/"),
        ).map {
            callback.invoke(
                newExtractorLink(
                    it.name,
                    it.name,
                    it.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = it.referer
                    this.headers = mapOf(
                        "Accept" to "*/*"
                    )
                }
            )
        }

    }

    suspend fun invokeVidsrccx(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val filePath =
            if (season == null) "/media/$tmdbId/master.m3u8" else "/media/$tmdbId-$season-$episode/master.m3u8"
        val video = app.post(
            "https://8ball.piracy.cloud/api/generate-secure-url", requestBody = mapOf(
                "filePath" to filePath
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).parsedSafe<VidsrccxSource>()?.secureUrl

        callback.invoke(
            newExtractorLink(
                "VidsrcCx",
                "VidsrcCx",
                video ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "${AdiDrakor.vidsrccxAPI}/"
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

    }

    suspend fun invokeSuperembed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        api: String = "https://streamingnow.mov"
    ) {
        val path = if (season == null) "" else "&s=$season&e=$episode"
        val token = app.get("${AdiDrakor.superembedAPI}/directstream.php?video_id=$tmdbId&tmdb=1$path").url.substringAfter(
            "?play="
        )

        val (server, id) = app.post(
            "$api/response.php", data = mapOf(
                "token" to token
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document.select("ul.sources-list li:contains(vipstream-S)")
            .let { it.attr("data-server") to it.attr("data-id") }

        val playUrl = "$api/playvideo.php?video_id=$id&server_id=$server&token=$token&init=1"
        val playRes = app.get(playUrl).document
        val iframe = playRes.selectFirst("iframe.source-frame")?.attr("src") ?: run {
            val captchaId = playRes.select("input[name=captcha_id]").attr("value")
            app.post(playUrl, requestBody = "captcha_id=TEduRVR6NmZ3Sk5Jc3JpZEJCSlhTM25GREs2RCswK0VQN2ZsclI5KzNKL2cyV3dIaFEwZzNRRHVwMzdqVmoxV0t2QlBrNjNTY04wY2NSaHlWYS9Jc09nb25wZTV2YmxDSXNRZVNuQUpuRW5nbkF2dURsQUdJWVpwOWxUZzU5Tnh0NXllQjdYUG83Y0ZVaG1XRGtPOTBudnZvN0RFK0wxdGZvYXpFKzVNM2U1a2lBMG40REJmQ042SA%3D%3D&captcha_answer%5B%5D=8yhbjraxqf3o&captcha_answer%5B%5D=10zxn5vi746w&captcha_answer%5B%5D=gxfpe17tdwub".toRequestBody(RequestBodyTypes.TEXT.toMediaTypeOrNull())
            ).document.selectFirst("iframe.source-frame")?.attr("src")
        }
        val json = app.get(iframe ?: return).text.substringAfter("Playerjs(").substringBefore(");")

        val video = """file:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)

        callback.invoke(
            newExtractorLink(
                "Superembed",
                "Superembed",
                video ?: return,
                INFER_TYPE
            ) {
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

        """subtitle:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)?.split(",")?.map {
            val (subLang, subUrl) = Regex("""\[(\w+)](http\S+)""").find(it)?.destructured
                ?: return@map
            subtitleCallback.invoke(
                newSubtitleFile(
                    subLang.trim(),
                    subUrl.trim()
                )
            )
        }

    }

    suspend fun invokeVidrock(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        subAPI: String = "https://sub.vdrk.site"
    ) {

        val type = if (season == null) "movie" else "tv"
        val url = "${AdiDrakor.vidrockAPI}/$type/$tmdbId${if (type == "movie") "" else "/$season/$episode"}"
        val encryptData = VidrockHelper.encrypt(tmdbId, type, season, episode)

        app.get("${AdiDrakor.vidrockAPI}/api/$type/$encryptData", referer = url).parsedSafe<LinkedHashMap<String, HashMap<String, String>>>()
            ?.map { source ->
                if (source.key == "source2") {
                    val json = app.get(source.value["url"] ?: return@map, referer = "${AdiDrakor.vidrockAPI}/").text
                    tryParseJson<ArrayList<VidrockSource>>(json)?.reversed()?.map mirror@{ it ->
                        callback.invoke(
                            newExtractorLink(
                                "Vidrock",
                                "Vidrock [Source2]",
                                it.url ?: return@mirror,
                                INFER_TYPE
                            ) {
                                this.quality = it.resolution ?: Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Range" to "bytes=0-",
                                    "Referer" to "${AdiDrakor.vidrockAPI}/"
                                )
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            "Vidrock",
                            "Vidrock [${source.key.capitalize()}]",
                            source.value["url"] ?: return@map,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "${AdiDrakor.vidrockAPI}/"
                            this.headers = mapOf(
                                "Origin" to AdiDrakor.vidrockAPI
                            )
                        }
                    )
                }
            }

        val subUrl = "$subAPI/$type/$tmdbId${if (type == "movie") "" else "/$season/$episode"}"
        val res = app.get(subUrl).text
        tryParseJson<ArrayList<VidrockSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.label?.replace(Regex("\\d"), "")?.replace(Regex("\\s+Hi"), "")?.trim()
                        ?: return@map,
                    subtitle.file ?: return@map,
                )
            )
        }

    }

    // ================== ADIDEWASA / DRAMAFULL SOURCE (NEW) ==================
    @Suppress("UNCHECKED_CAST")
    suspend fun invokeAdiDewasa(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = "https://dramafull.cc"
        
        // 1. PEMBERSIHAN JUDUL
        val cleanQuery = AdiDewasaHelper.normalizeQuery(title)
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val searchUrl = "$baseUrl/api/live-search/$encodedQuery"

        try {
            // Gunakan Header untuk SEARCHING (Wajib ada)
            val searchRes = app.get(searchUrl, headers = AdiDewasaHelper.headers).parsedSafe<AdiDewasaSearchResponse>()
            
            // 2. PENCOCOKAN JUDUL
            val matchedItem = searchRes?.data?.find { item ->
                val itemTitle = item.title ?: item.name ?: ""
                AdiDewasaHelper.isFuzzyMatch(title, itemTitle)
            } ?: searchRes?.data?.firstOrNull()

            if (matchedItem == null) return 

            val slug = matchedItem.slug ?: return
            var targetUrl = "$baseUrl/film/$slug"

            // 3. LOAD HALAMAN FILM
            val doc = app.get(targetUrl, headers = AdiDewasaHelper.headers).document

            if (season != null && episode != null) {
                // SERIAL TV
                val episodeHref = doc.select("div.episode-item a, .episode-list a").find { 
                    val text = it.text().trim()
                    val epNum = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    epNum == episode
                }?.attr("href")

                if (episodeHref == null) return
                targetUrl = fixUrl(episodeHref, baseUrl)
            } else {
                // FILM MOVIE
                val selectors = listOf(
                    "a.btn-watch", 
                    "a.watch-now", 
                    ".watch-button a", 
                    "div.last-episode a",
                    ".film-buttons a.btn-primary"
                )
                
                var foundUrl: String? = null
                for (selector in selectors) {
                    val el = doc.selectFirst(selector)
                    if (el != null) {
                        val href = el.attr("href")
                        if (href.isNotEmpty() && !href.contains("javascript") && href != "#") {
                            foundUrl = fixUrl(href, baseUrl)
                            break
                        }
                    }
                }
                if (foundUrl != null) targetUrl = foundUrl
            }

            // 5. EKSTRAKSI VIDEO (HEADER PLAYER DIHAPUS)
            val docPage = app.get(targetUrl, headers = AdiDewasaHelper.headers).document
            val allScripts = docPage.select("script").joinToString(" ") { it.data() }
            
            val signedUrl = Regex("""signedUrl\s*=\s*["']([^"']+)["']""").find(allScripts)?.groupValues?.get(1)?.replace("\\/", "/") 
                ?: return
            
            val jsonResponseText = app.get(signedUrl, referer = targetUrl, headers = AdiDewasaHelper.headers).text
            val jsonObject = tryParseJson<Map<String, Any>>(jsonResponseText) ?: return
            val videoSource = jsonObject["video_source"] as? Map<String, String> ?: return
            
            videoSource.forEach { (quality, url) ->
                 if (url.isNotEmpty()) {
                    callback.invoke(
                        // Hapus Referer di link video agar bisa diputar
                        newExtractorLink(
                            "AdiDewasa",
                            "AdiDewasa ($quality)",
                            url,
                            INFER_TYPE 
                        )
                    )
                }
            }
             
             // SUBTITLE
             val bestQualityKey = videoSource.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return
             val subJson = jsonObject["sub"] as? Map<String, Any>
             val subs = subJson?.get(bestQualityKey) as? List<String>
             subs?.forEach { subPath ->
                 val subUrl = fixUrl(subPath, baseUrl)
                 subtitleCallback.invoke(
                     newSubtitleFile("English", subUrl)
                 )
             }
             
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ================== KISSKH SOURCE (INTEGRATED) ==================
    // NEW ADDITION: Asian Drama & Anime Source from Kisskh
    
    suspend fun invokeKisskh(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        // Kunci API Rahasia yang ditemukan di classes.dex (Source 294 & 332)
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        try {
            // 1. SEARCH
            val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$title&type=0").text
            val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return

            // 2. MATCHING
            val matched = searchList.find { 
                it.title.equals(title, true) 
            } ?: searchList.firstOrNull { it.title?.contains(title, true) == true } ?: return

            val dramaId = matched.id ?: return
            
            // 3. GET EPISODE LIST
            val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
            val episodes = detailRes.episodes ?: return

            // 4. FIND TARGET EPISODE
            val targetEp = if (season == null) {
                // Movie: Ambil episode terakhir/satu-satunya
                episodes.lastOrNull()
            } else {
                // Series: Hitung berdasarkan season & episode
                // Kisskh biasanya list flat, jadi kita coba cari berdasarkan urutan atau asumsi
                // Logika sederhana: Episode request user harus match dengan 'number' di API
                episodes.find { it.number?.toInt() == episode }
            } ?: return

            val epsId = targetEp.id ?: return

            // 5. GET KKEY (Video Token)
            val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""

            // 6. GET VIDEO SOURCES
            // Endpoint disamarkan sebagai .png
            val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkeyVideo"
            val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

            val videoLink = sources?.video
            val thirdParty = sources?.thirdParty

            listOfNotNull(videoLink, thirdParty).forEach { link ->
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        "Kisskh",
                        link,
                        referer = "$mainUrl/",
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(
                            "Kisskh",
                            "Kisskh",
                            link,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }

            // 7. GET SUBTITLES
            val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
            
            tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.label ?: "Unknown",
                        sub.src ?: return@forEach
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Data Classes Khusus Kisskh (Private agar tidak konflik)
    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

}
