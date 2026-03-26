package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.utils.Qualities

class Melolo : MainAPI() {
    override var mainUrl = "https://melolo-api-azure.vercel.app"
    override var name = "Melolo"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/api/melolo/latest" to "Latest",
        "$mainUrl/api/melolo/trending" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(request.data).parsedSafe<MeloloListResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data halaman utama")
        
        val home = res.books?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/melolo/search?query=$query&limit=10&offset=0"
        val res = app.get(url).parsedSafe<MeloloSearchResponse>() ?: return emptyList()
        return res.searchData?.flatMap { group ->
            group.books?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/")
        val detailUrl = "$mainUrl/api/melolo/detail/$bookId"
        
        val res = app.get(detailUrl).parsedSafe<MeloloDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal memuat detail drama")
            
        val videoData = res.data?.videoData ?: throw ErrorLoadingException("Data tidak ditemukan")

        val episodes = videoData.videoList?.map { ep ->
            val epData = MeloloLinkData(ep.vid ?: "").toJson()
            newEpisode(epData) {
                this.name = ep.title
                this.episode = ep.vidIndex
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(videoData.seriesTitle ?: "No Title", url, TvType.AsianDrama, episodes) {
            this.posterUrl = videoData.seriesCover ?: res.data?.bookData?.cover
            this.plot = videoData.seriesIntro ?: res.data?.bookData?.intro
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        android.util.Log.d("Melolo", "loadLinks data: $data")
        val lid = try { parseJson<MeloloLinkData>(data) } catch(e: Exception) { 
            android.util.Log.e("Melolo", "Error parsing lid: $e")
            return false 
        }
        
        val streamUrl = "$mainUrl/api/melolo/stream/${lid.id}"
        android.util.Log.d("Melolo", "Requesting stream: $streamUrl")
        val responseText = app.get(streamUrl).text
        
        val rootMap = try { parseJson<Map<String, Any?>>(responseText) } catch(e: Exception) {
            android.util.Log.e("Melolo", "Failed to parse root response: ${responseText.take(200)}")
            return false
        }
        
        val dataObj = rootMap["data"] as? Map<String, Any?> ?: rootMap
        val videoModelRaw = dataObj["video_model"]
        
        val videoModelMap = when (videoModelRaw) {
            is String -> try { parseJson<Map<String, Any?>>(videoModelRaw) } catch(e: Exception) { 
                android.util.Log.e("Melolo", "Failed to parse video_model string")
                null 
            }
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (videoModelRaw as Map<String, Any?>)
            else -> null
        }
        
        if (videoModelMap == null) {
            android.util.Log.e("Melolo", "video_model not found or invalid. Keys: ${dataObj.keys}")
            return false
        }
        
        try {
            android.util.Log.d("Melolo", "Inner JSON keys: ${videoModelMap.keys}")
            
            videoModelMap["video_list"]?.let { android.util.Log.d("Melolo", "video_list content: $it") }
            videoModelMap["fallback_api"]?.let { android.util.Log.d("Melolo", "fallback_api content: $it") }

            // Extensive search for video information
            @Suppress("UNCHECKED_CAST")
            val videoInfo = (videoModelMap["video_info"] as? Map<String, Any?>)
                ?: (videoModelMap["video_model"] as? Map<String, Any?>)?.get("video_info") as? Map<String, Any?>
                ?: (videoModelMap["video"] as? Map<String, Any?>)
                ?: videoModelMap
            
            @Suppress("UNCHECKED_CAST")
            val videos = (videoInfo["data"] as? Map<String, Any?>)
                ?: (videoInfo["video_list"] as? Map<String, Any?>)
                ?: (if (videoInfo.containsKey("main_url") || videoInfo.containsKey("backup_url_1")) mapOf("default" to videoInfo) else null)
            
            if (videos == null) {
                android.util.Log.e("Melolo", "Could not find video data. Keys in videoModelMap: ${videoModelMap.keys}")
                videoModelMap["video"]?.let { android.util.Log.d("Melolo", "video content: $it") }
                return false
            }

            var foundLinks = 0
            videos.forEach { (qualityKey, videoObj) ->
                val videoData = videoObj as? Map<String, Any?> ?: return@forEach
                android.util.Log.d("Melolo", "Processing quality entry: $qualityKey")
                
                val mainUrlEncoded = videoData["main_url"] as? String
                val backupUrlEncoded = videoData["backup_url_1"] as? String
                
                val quality = when(qualityKey) {
                    "10", "360p" -> Qualities.P360.value
                    "20", "480p" -> Qualities.P480.value
                    "30", "720p" -> Qualities.P720.value
                    "40", "1080p" -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }

                mainUrlEncoded?.decodeBase64()?.let { mainUrl ->
                    android.util.Log.d("Melolo", "Found main ($qualityKey): $mainUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Main ${if(qualityKey == "default") "" else qualityKey}".trim(),
                            mainUrl,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                        }
                    )
                    foundLinks++
                }

                backupUrlEncoded?.decodeBase64()?.let { backupUrl ->
                    android.util.Log.d("Melolo", "Found backup ($qualityKey): $backupUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Backup ${if(qualityKey == "default") "" else qualityKey}".trim(),
                            backupUrl,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                        }
                    )
                    foundLinks++
                }
            }
            
            android.util.Log.d("Melolo", "Total links found: $foundLinks")
            return foundLinks > 0
        } catch (e: Exception) {
            android.util.Log.e("Melolo", "Error in link processing: $e")
            return false
        }
    }
    
    private fun String.decodeBase64(): String? {
        return try {
            // The API returns URL-encoded Base64 strings (e.g. %3D instead of =)
            val unescaped = java.net.URLDecoder.decode(this, "UTF-8")
            val decoded = android.util.Base64.decode(unescaped, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("Melolo", "Base64 decode failed for $this: $e")
            null
        }
    }

    // Data Classes
    data class MeloloLinkData(val id: String)

    data class MeloloListResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("books") val books: List<MeloloBook>? = null
    )

    data class MeloloSearchResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("search_data") val searchData: List<MeloloSearchGroup>? = null
    )

    data class MeloloSearchGroup(
        @JsonProperty("books") val books: List<MeloloBook>? = null
    )

    data class MeloloBook(
        @JsonProperty("book_id") val bookId: String? = null,
        @JsonProperty("book_name") val bookName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("intro") val intro: String? = null
    ) {
        fun toSearchResponse(api: Melolo): SearchResponse? {
            val id = bookId ?: return null
            val title = bookName ?: return null
            return api.newAnimeSearchResponse(title, "${api.mainUrl}/$id", TvType.AsianDrama, false) {
                 this.posterUrl = thumbUrl ?: cover
            }
        }
    }

    data class MeloloDetailResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("data") val data: MeloloDetailData? = null
    )

    data class MeloloDetailData(
        @JsonProperty("video_data") val videoData: MeloloVideoData? = null,
        @JsonProperty("book_data") val bookData: MeloloBook? = null
    )

    data class MeloloVideoData(
        @JsonProperty("series_title") val seriesTitle: String? = null,
        @JsonProperty("series_intro") val seriesIntro: String? = null,
        @JsonProperty("series_cover") val seriesCover: String? = null,
        @JsonProperty("video_list") val videoList: List<MeloloEpisode>? = null
    )

    data class MeloloEpisode(
        @JsonProperty("vid") val vid: String? = null,
        @JsonProperty("vid_index") val vidIndex: Int? = null,
        @JsonProperty("title") val title: String? = null
    )
    
    data class MeloloStreamResponse(
         @JsonProperty("data") val data: MeloloStreamResult? = null,
         @JsonProperty("debug_info") val debugInfo: String? = null,
         @JsonProperty("log_id") val logId: String? = null,
         @JsonProperty("message") val message: String? = null
    )
    
    data class MeloloStreamResult(
        @JsonProperty("video_model") val videoModel: String? = null
    )

    data class MeloloVideoModel(
        @JsonProperty("video_model") val videoModel: MeloloVideoModelInner? = null,
        @JsonProperty("video_info") val videoInfo: MeloloVideoInfoWrapper? = null,
    )

    data class MeloloVideoModelInner(
        @JsonProperty("video_info") val videoInfo: MeloloVideoInfoWrapper? = null
    )

    data class MeloloVideoInfoWrapper(
        @JsonProperty("data") val data: Map<String, MeloloVideoSource>? = null
    )

    data class MeloloVideoSource(
        @JsonProperty("main_url") val mainUrl: String? = null,
        @JsonProperty("backup_url_1") val backupUrl: String? = null
    )
}
