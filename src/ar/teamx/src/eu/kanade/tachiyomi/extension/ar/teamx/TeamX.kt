package eu.kanade.tachiyomi.extension.ar.teamx

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

open class TeamX : ParsedHttpSource() {
    override val name = "Teamx"

    override val baseUrl = "https://mnhaestate.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page", headers)
    }

    override fun popularMangaSelector() = "div.bs"

    override fun popularMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            element.select("img").let {
                thumbnail_url = it.attr("abs:src")
            }
            element.select("a").let {
                title = it.attr("title")
            }
        }

    override fun popularMangaNextPageSelector(): String? = "a.page-link[rel=\"next\"]"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesSelector() = "div.box"

    override fun latestUpdatesFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.select("div.imgu > a").attr("abs:href"))
            element.select("div.imgu > a > img").let {
                thumbnail_url = it.attr("abs:src")
            }
            element.select("div.imgu > a > img").let {
                title = it.attr("alt")
            }
        }

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/ajax/search?keyword=$query", headers)
        } else {

            val url = "$baseUrl/series?page=$page".toHttpUrl().newBuilder()
            filters.forEach { filter ->
                when (filter) {

                    is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())

                    is TypeFilter -> url.addQueryParameter("type", filter.toUriPart())

                    is GenreFilter -> {
                        filter.state
                            .filter { it.state != Filter.TriState.STATE_IGNORE }
                            .forEach { url.addQueryParameter("genre[]", it.id) }
                    }

                    else -> {}
                }
            }
            return GET(url.toString(), headers)
        }
    }

    override fun searchMangaSelector() = "li"

    override fun searchMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            element.select("img").let {
                thumbnail_url = it.attr("abs:src")
            }
            element.select("a.fw-bold").let {
                title = it.text()
            }
        }

    override fun searchMangaNextPageSelector() = "a.page-link[rel=\"next\"]"

    // Manga summary page
    open val altNamePrefix = "Alternative Name: "
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val infoElement = document.select("div.row.mb-5").first()
        title = infoElement.select("div.author-info-title.mb-3 > h1").text().removeSurrounding("(", ")")
        author = infoElement.select("div.text-right > div:nth-child(7) > small:nth-child(2) > a").text()
        artist = author
        status = parseStatus(infoElement.select("div.text-right > div:nth-child(6) > small:nth-child(2) > a").text())
        genre = infoElement.select("div.review-author-info > a").joinToString(", ") { it.text() }
        thumbnail_url = infoElement.select("div.text-right > img").attr("src")
        description = infoElement.select("div.review-content > p").text()
        val altName = infoElement.select("span.alternative").text().takeIf { it.isNullOrBlank().not() }
        altName?.let {
            description = "$description\n\n$altNamePrefix$altName".trim()
        }
        val type = infoElement.select("div.text-right > div:nth-child(5) > small:nth-child(2) > a").text().takeIf { it.isNullOrBlank().not() }
        type?.let {
            genre = "$genre, $type".trim()
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("مستمرة") -> SManga.ONGOING
        status.contains("مكتمل") -> SManga.COMPLETED
        status.contains("متوقف") -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
    // chapiter

    private fun chapterNextPageSelector() = "a[aria-label=\"Next »\"]"

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var document = response.asJsoup()

        while (true) {
            val pageChapters = document.select(chapterListSelector()).map { chapterFromElement(it) }
            if (pageChapters.isEmpty())
                break

            allChapters += pageChapters

            val hasNextPage = document.select(chapterNextPageSelector()).isNotEmpty()
            if (!hasNextPage)
                break

            val nextUrl = document.select(chapterNextPageSelector()).attr("href")
            document = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
        }

        return allChapters
    }

    // Filter out the fake chapters
    override fun chapterListSelector() = "div.eplister > ul > li"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            val urlElements = element.select("a")
            setUrlWithoutDomain(urlElements.attr("href"))
            name = element.select("a > div:nth-child(2)").text().ifBlank { urlElements.first().text() }
            // chapter_number = element.select(".lch a, div.epl-num").text().ifBlank { urlElements.first().text() }.toFloat()
            date_upload = element.selectFirst("div.epl-date.eph-date.d-none.d-sm-block.date-time")?.text().parseChapterDate()
//            name = element.text()
//            setUrlWithoutDomain(element.attr("href"))
        }
    }
/*
    override fun chapterListSelector() = "div.eplister > ul > li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {

        val urlElements = element.select("a")
        setUrlWithoutDomain(urlElements.attr("href"))
        name = element.select("a > div:nth-child(2)").text().ifBlank { urlElements.first().text() }
        // chapter_number = element.select(".lch a, div.epl-num").text().ifBlank { urlElements.first().text() }.toFloat()
        date_upload = element.selectFirst("div.epl-date.eph-date.d-none.d-sm-block.date-time")?.text().parseChapterDate()

    }

 */
    open fun String?.parseChapterDate(): Long {
        if (this == null) return 0
        return try {
            dateFormat.parse(this)?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("body > section > div > div > div > div.main-col-inner > div.c-blog-post > div.entry-content > div > div > div.reading-content > div.image_list > div.page-break.no-gaps >img").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = FilterList(
        StatusFilter(getStatusFilters()),
        TypeFilter(getTypeFilter()),
        Filter.Separator(),
        Filter.Header("exclusion not available for This source"),
        GenreFilter(getGenreFilters()),
    )

    private class TypeFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Type", vals)

    private class StatusFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Status", vals)

    class Genre(name: String, val id: String = name) : Filter.TriState(name)
    private class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

    private fun getStatusFilters(): Array<Pair<String?, String>> = arrayOf(
        Pair("", ""),
        Pair("مستمرة", "مستمرة"),
        Pair("مستمرة", "متوقف"),
        Pair("مكتمل", "مكتمل"),
        Pair("مكتمل", "قادم قريبًا")

    )

    private fun getTypeFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("", ""),
        Pair("مانجا ياباني", "مانجا ياباني"),
        Pair("مانها صيني", "مانها صيني"),
        Pair("مانها أندونيسية", "مانها أندونيسية"),
        Pair("ويب تون انجليزية", "ويب تون انجليزية"),
        Pair("مانهوا كورية", "مانهوا كورية"),
    )

    open fun getGenreFilters(): List<Genre> = listOf(
        Genre("آلات", "آلات"),
        Genre("أكشن", "أكشن"),
        Genre("إثارة", "إثارة"),
        Genre("إعادة إحياء", "إعادة إحياء"),
        Genre("اكشن", "اكشن"),
        Genre("اكشن، مغامرات،دراما، فنون قتال،شونين", "اكشن، مغامرات،دراما، فنون قتال،شونين"),
        Genre("الحياة المدرسية", "الحياة المدرسية"),
        Genre("الحياة اليومية", "الحياة اليومية"),
        Genre("العاب فيديو", "العاب فيديو"),
        Genre("ايتشي", "ايتشي"),
        Genre("ايسكاي", "ايسكاي"),
        Genre("بالغ", "بالغ"),
        Genre("تاريخي", "تاريخي"),
        Genre("تراجيدي", "تراجيدي"),
        Genre("تناسخ", "تناسخ"),
        Genre("جريمة", "جريمة"),
        Genre("جوسيه", "جوسيه"),
        Genre("جيندر بندر", "جيندر بندر"),
        Genre("حديث", "ث"),
        Genre("حربي", "ي"),
        Genre("حريم", "م"),
        Genre("خارق للطبيعة", "ق-للطبيعة"),
        Genre("خيال", "خيال"),
        Genre("خيال علمي", "خيال علمي"),
        Genre("خيالي", "خيالي"),
        Genre("دراما", "دراما"),
        Genre("دموي", "دموي"),
        Genre("راشد", "راشد"),
        Genre("رعب", "رعب"),
        Genre("رومانسي", "رومانسي"),
        Genre("رياضة", "رياضة"),
        Genre("زراعة", "زراعة"),
        Genre("زمكاني", "زمكاني"),
        Genre("زومبي", "زومبي"),
        Genre("سحر", "سحر"),
        Genre("سينين", "سينين"),
        Genre("شريحة من الحياة", "شريحة من الحياة"),
        Genre("شوجو", "شوجو"),
        Genre("شونين", "شونين"),
        Genre("شياطين", "شياطين"),
        Genre("طبخ", "طبخ"),
        Genre("طبي", "طبي"),
        Genre("عسكري", "عسكري"),
        Genre("غموض", "غموض"),
        Genre("فانتازي", "فانتازي"),
        Genre("فنون قتال", "فنون قتال"),
        Genre("فنون قتالية", "فنون قتالية"),
        Genre("فوق الطبيعة", "فوق الطبيعة"),
        Genre("قوى خارقة", "قوى خارقة"),
        Genre("كوميدي", "كوميدي"),
        Genre("لعبة", "لعبة"),
        Genre("مأساوي", "مأساوي"),
        Genre("مافيا", "مافيا"),
        Genre("مصاصى الدماء", "مصاصى الدماء"),
        Genre("مغامرات", "مغامرات"),
        Genre("موريم", "موريم"),
        Genre("موسيقى", "موسيقى"),
        Genre("ميشا", "ميشا"),
        Genre("ميكا", "ميكا"),
        Genre("نظام", "نظام"),
        Genre("نفسي", "نفسي"),
        Genre("وحوش", "وحوش"),
        Genre("ويب-تون", "ويب-تون")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String?, String>>) :
        Filter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }
}
