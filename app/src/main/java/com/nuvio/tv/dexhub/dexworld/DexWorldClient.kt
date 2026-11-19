package com.nuvio.tv.dexhub.dexworld

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DexWorld's ready sections (https://blocks.dexworld.cc), ported from Flix (FlixBridge/Nuvio/DexWorld.swift): Gulf,
 * Egyptian, Turkish and Levantine series, Arabic films, Hollywood, Korean, anime, the platforms and the well-known
 * lists. `/api/dexhub?lang=ar|en` lists the page's rows in order; `/api/dexhub/row?id=&lang=&page=` is a row's whole
 * list, 40 titles a page. The page is kept on disk and asked again after half an hour with its ETag; when the site
 * does not answer, the copy on disk is used however old.
 */
class DexWorldClient(private val folder: File, private val http: OkHttpClient = defaultHttp) {

    data class Member(val id: String, val title: String, val cover: String?)

    data class Row(
        val id: String,
        val kind: String?,
        val title: String,
        val cover: String?,
        val members: List<Member>,
        val items: List<Item>
    )

    data class Item(
        val type: String,
        val tmdb: Int?,
        val imdb: String?,
        val title: String,
        val year: String?,
        val rating: Double?,
        val poster: String?,
        val backdrop: String?
    ) {
        /** The id a title page opens by: IMDb's when the site has it, else TMDB's. */
        val contentId: String?
            get() = imdb?.takeIf { it.startsWith("tt") } ?: tmdb?.let { "tmdb:$it" }

        /** "movie" or "series", as the title page takes them. */
        val contentType: String get() = if (type == "movie") "movie" else "series"
    }

    /** A shelf of the page: a kit's members, or the rows that belong to no kit gathered as "DexWorld". */
    data class Shelf(val id: String, val title: String, val sections: List<Member>)

    /** The page in [language] ("ar" or anything else, which reads the English page). */
    fun page(language: String): List<Row>? {
        val lang = language(language)
        val file = File(folder, "page-$lang.json")
        val held = runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull()
        val heldPage = held?.optJSONObject("page")
        if (heldPage != null && System.currentTimeMillis() - (held?.optLong("at") ?: 0L) < FreshFor) return rows(heldPage)
        val fetched = runCatching {
            val request = Request.Builder().url(Base.toHttpUrl().newBuilder().addPathSegments("api/dexhub")
                .addQueryParameter("lang", lang).build()).header("User-Agent", Agent)
            held?.optString("etag")?.takeIf { it.isNotEmpty() }?.let { request.header("If-None-Match", it) }
            http.newCall(request.build()).execute().use { response ->
                val page = when {
                    response.code == 304 && heldPage != null -> heldPage
                    response.isSuccessful -> JSONObject(response.body?.string().orEmpty())
                    else -> return@use null
                }
                if ((page.optJSONArray("rows")?.length() ?: 0) == 0) return@use null
                val etag = response.header("ETag") ?: held?.optString("etag").orEmpty()
                folder.mkdirs()
                file.writeText(JSONObject().put("at", System.currentTimeMillis()).put("etag", etag).put("page", page).toString())
                page
            }
        }.getOrNull()
        return (fetched ?: heldPage)?.let(::rows)
    }

    /** A row's titles, up to three pages (120). */
    fun items(rowId: String, language: String): List<Item>? {
        val all = ArrayList<Item>()
        var page = 1
        while (page <= PageLimit) {
            val url = Base.toHttpUrl().newBuilder().addPathSegments("api/dexhub/row")
                .addQueryParameter("id", rowId).addQueryParameter("lang", language(language))
                .addQueryParameter("page", page.toString()).build()
            val found = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", Agent).build()).execute().use { response ->
                    if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
                }
            }.getOrNull() ?: return all.takeIf { it.isNotEmpty() }
            all += items(found.optJSONArray("items"))
            if (found.optInt("page") >= found.optInt("pages")) break
            page++
        }
        return all
    }

    companion object {
        const val Base = "https://blocks.dexworld.cc"
        private const val Agent = "DexHubTV"
        private const val FreshFor = 30 * 60_000L
        private const val PageLimit = 3
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()

        /** Arabic reads the Arabic page; any other language the English one. */
        fun language(code: String): String = if (code.lowercase().startsWith("ar")) "ar" else "en"

        fun rows(page: JSONObject): List<Row> {
            val list = page.optJSONArray("rows") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val row = list.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id").ifEmpty { return@mapNotNull null }
                val members = row.optJSONArray("members")?.let { m ->
                    (0 until m.length()).mapNotNull { j ->
                        val member = m.optJSONObject(j) ?: return@mapNotNull null
                        val memberId = member.optString("id").ifEmpty { return@mapNotNull null }
                        Member(memberId, member.optString("title"), member.text("cover"))
                    }
                }.orEmpty()
                Row(id, row.text("kind"), row.optString("title"), row.text("cover"), members, items(row.optJSONArray("items")))
            }
        }

        fun items(list: JSONArray?): List<Item> {
            list ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    type = item.optString("type"),
                    tmdb = item.opt("tmdb")?.toString()?.toDoubleOrNull()?.toInt(),
                    imdb = item.text("imdb"),
                    title = item.optString("title"),
                    year = item.text("year"),
                    rating = item.opt("rating")?.toString()?.toDoubleOrNull(),
                    poster = item.text("poster"),
                    backdrop = item.text("backdrop")
                ).takeIf { it.contentId != null }
            }
        }

        /**
         * The page as shelves, as Flix lays out its collections: each kit (a row of rows) is a shelf of its members
         * with the site's covers; every other row is a section of one last "DexWorld" shelf. A row's cover, or its
         * first title's backdrop, is the section's picture.
         */
        fun shelves(rows: List<Row>): List<Shelf> {
            val covers = HashMap<String, String>()
            for (row in rows) {
                row.members.forEach { member -> member.cover?.let { covers[member.id] = it } }
                row.cover?.let { covers[row.id] = it }
            }
            for (row in rows) {
                if (row.id !in covers) row.items.firstNotNullOfOrNull { it.backdrop }?.let { covers[row.id] = it }
            }
            val shelves = ArrayList<Shelf>()
            val loose = ArrayList<Member>()
            for (row in rows) {
                if (row.kind == "kit") {
                    if (row.members.isNotEmpty()) {
                        shelves += Shelf(row.id, row.title,
                            row.members.distinctBy { it.id }.map { it.copy(cover = covers[it.id] ?: it.cover) })
                    }
                } else if (loose.none { it.id == row.id }) {
                    loose += Member(row.id, row.title, covers[row.id])
                }
            }
            if (loose.isNotEmpty()) shelves += Shelf(":rows", "DexWorld", loose)
            return shelves
        }

        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
