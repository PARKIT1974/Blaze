package com.dramaid

import android.util.Base64
import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class DramaIdProvider : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "DramaID💖"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Drama Terbaru",
        "/status-drama/ongoing/" to "Ongoing",
        "/status-drama/complete/" to "Drama Completed",
        "/genre/romance/" to "Romance",
        "/genre/sci-fi/" to "Sci-Fi",
        "/negara/korea-selatan/" to "Drama Korea",
        "/negara/china/" to "Drama China",
        "/negara/japan/" to "Drama Jepang",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) {
            LicenseClient.requireLicense(this.name, "HOME")
        }
        val url = if (request.data.isBlank())
            "$mainUrl/page/$page/"
        else
            "$mainUrl${request.data}page/$page/"

        val doc = app.get(url).document

        val home = doc.select("div.post_index article").mapNotNull { el ->
            val a = el.selectFirst("h3.title_post a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href")).substringBefore("?").trimEnd('/')

            if (href.contains("#") || href.contains("javascript") || href.contains("/episode/"))
                return@mapNotNull null

            val title = a.text().replace("Subtitle Indonesia", "").trim()
            val poster = el.selectFirst("img")?.attr("src")

            val episodeText = el.select("ul li:contains(Episode)").text()
            val latestEp = Regex("(\\d+)(?!.*\\d)")
                .find(episodeText)
                ?.value

            val scoreText = el.select("ul li:contains(Score)").text()
            val score = Regex("(\\d+(\\.\\d+)?)")
                .find(scoreText)
                ?.value
                ?.toDoubleOrNull()

            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD

                if (!latestEp.isNullOrBlank()) {
                    this.addQuality("Sub Ep $latestEp")
                }

                this.score = score?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home)),
            hasNext = doc.selectFirst("link[rel=next]") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.requireLicense(this.name, "SEARCH", query)
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document

        return doc.select("h3.title_post").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))

            if (href.contains("#") || href.contains("javascript"))
                return@mapNotNull null

            val title = a.text().trim()
            val poster = it.parent()?.selectFirst("img")?.attr("src")

            val episodeText = it.parent()?.select("ul li:contains(Episode)")?.text()
            val latestEp = Regex("(\\d+)(?!.*\\d)")
                .find(episodeText ?: "")
                ?.value

            val scoreText = it.parent()?.select("ul li:contains(Score)")?.text()
            val score = Regex("(\\d+(\\.\\d+)?)")
                .find(scoreText ?: "")
                ?.value
                ?.toDoubleOrNull()

            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD

                if (!latestEp.isNullOrBlank()) {
                    this.addQuality("Sub Ep $latestEp")
                }

                this.score = score?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.requireLicense(this.name, "LOAD", url)
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.single-title, h2.single-title")
            ?.text()?.trim() ?: "No Title"

        val poster = doc.selectFirst(".thumbnail_single img, .daftar-foto img")
            ?.attr("src")

        val plot = doc.select(".synopsis p")
            .joinToString("\n") { it.text() }
            .trim()

        val infoMap = doc.select(".info ul li").associate {
            val key = it.selectFirst("strong")
                ?.text()?.replace(":", "")?.trim() ?: ""

            val value = it.select("a")
                .joinToString(", ") { a -> a.text() }
                .ifEmpty { it.ownText().trim() }

            key to value
        }

        val type = infoMap["Tipe"]?.lowercase()
        val isMovie = type?.contains("movie") == true

        val year = infoMap["Tahun"]?.toIntOrNull()

        val status = if (infoMap["Status"]?.contains("ongoing", true) == true)
            ShowStatus.Ongoing else ShowStatus.Completed

        val score = infoMap["Skor"]
            ?.replace(",", ".")
            ?.substringBefore("/")
            ?.toDoubleOrNull()

        val tags = doc.select(".info ul li a").map { it.text() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score?.let { Score.from10(it) }
                this.tags = tags
            }
        }

        val episodes = doc.select(".daftar-episode a").mapIndexed { i, el ->
            newEpisode(fixUrl(el.attr("href"))) {
                this.name = "Episode ${i + 1}"
                this.episode = i + 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score?.let { Score.from10(it) }
            this.showStatus = status
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.requireLicense(this.name, "PLAY", data)

        val doc = app.get(data).document
        var found = false

        val qualityMap = mutableMapOf<Int, Pair<String, String>>()

        doc.select(".resolusi-list li").forEach { el ->
            val encoded = el.attr("data")
            if (encoded.isBlank()) return@forEach

            try {
                val jsonStr = String(Base64.decode(encoded, Base64.DEFAULT))
                val obj = JSONObject(jsonStr)

                val rawRes = obj.optString("resolution")

                val resNum = Regex("(\\d{3,4})")
                    .find(rawRes)
                    ?.value
                    ?.toIntOrNull() ?: return@forEach

                val cleanRes = "${resNum}p"

                if (qualityMap.containsKey(resNum)) return@forEach

                val links = obj.getJSONArray("links")

                for (i in 0 until links.length()) {
                    var url = links.getJSONObject(i).getString("url")
                    url = url.replace("\\/", "/")

                    val id = Uri.parse(url).getQueryParameter("id") ?: continue

                    val api = app.get("https://api.dlgan.space/api.php?id=$id").text
                    val direct = JSONObject(api).optString("direct_url")

                    if (direct.isNotEmpty()) {
                        qualityMap[resNum] = cleanRes to direct
                        break
                    }
                }

            } catch (_: Exception) {}
        }

        qualityMap
            .toSortedMap()
            .forEach { (_, pair) ->
                val (res, link) = pair

                found = true
                callback.invoke(
                    newExtractorLink(
                        "DramaID",
                        "DramaID",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(res)
                    }
                )
            }

        return found
    }
}
