package eu.kanade.tachiyomi.extension.ar.aresmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

open class AresManga : ParsedHttpSource() {
    override val name = "Aresmanga"

    override val baseUrl = "https://aresmanga.net/"

    override val lang = "ar"

    override val supportsLatest = true

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMMM dd, yyyy", Locale("ar"))

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series/?page=$page&order=popular", headers)
    }

    override fun popularMangaSelector() = "div.bsx"

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

    override fun popularMangaNextPageSelector(): String? = "a.r"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/series/?page=$page&order=update", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series/?page=$page".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("order", filter.toUriPart())

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

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga summary page
    open val altNamePrefix = "Alternative Name: "
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val infoElement = document.select("div.main-info").first()

        title = infoElement.select("h1.entry-title").text().removeSurrounding("(", ")")
        author = infoElement.select("div.info-left > div > div.tsinfo.bixbox > div:nth-child(4) > i").text()
        artist = infoElement.select("div.info-left > div > div.tsinfo.bixbox > div:nth-child(5) > i").text()
        status = parseStatus(infoElement.select("div.info-left > div > div.tsinfo.bixbox > div:nth-child(1) > i").text())
        genre = infoElement.select("span.mgen > a").joinToString(", ") { it.text() }
        thumbnail_url = infoElement.select("div.info-left > div > div.thumb > img").attr("src")
        description = infoElement.select("div.info-right > div.info-desc.bixbox > div:nth-child(3) > div > p").text()
        val altName = infoElement.select("span.alternative").text().takeIf { it.isNullOrBlank().not() }
        altName?.let {
            description = "$description\n\n$altNamePrefix$altName".trim()
        }
        val type = infoElement.select("div.info-left > div > div.tsinfo.bixbox > div:nth-child(2) > a").text().takeIf { it.isNullOrBlank().not() }
        type?.let {
            genre = "$genre, $type".trim()
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        status.contains("Hiatus") -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.eplister > ul > li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val urlElements = element.select("a")
        setUrlWithoutDomain(urlElements.attr("href"))
        name = element.select(".lch a, .chapternum").text().ifBlank { urlElements.first().text() }
        date_upload = element.selectFirst("span.chapterdate")?.text().parseChapterDate()
    }

    open fun String?.parseChapterDate(): Long {
        if (this == null) return 0
        return try {
            dateFormat.parse(this)?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val page: List<Page>?
        val scriptContent = document.selectFirst("script:containsData(ts_reader)").data()
        val removeHead = scriptContent.replace("ts_reader.run(", "").replace(");", "")
        val jsonObject = JSONObject(removeHead)
        val sourcesArray = jsonObject.getJSONArray("sources")
        val imagesArray = sourcesArray.getJSONObject(0).getJSONArray("images")
        page = List(imagesArray.length()) { i ->
            Page(i, "", imagesArray[i].toString())
        }

        return page
    }

    override fun imageUrlParse(document: Document): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = FilterList(
        SortFilter(getSortFilters()),
        StatusFilter(getStatusFilters()),
        TypeFilter(getTypeFilter()),
        Filter.Separator(),
        Filter.Header("exclusion not available for This source"),
        GenreFilter(getGenreFilters()),
    )

    private class SortFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Sort by", vals)

    private class TypeFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Type", vals)

    private class StatusFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Status", vals)

    class Genre(name: String, val id: String = name) : Filter.TriState(name)
    private class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

    private fun getSortFilters(): Array<Pair<String?, String>> = arrayOf(
        Pair("title", "A-Z"),
        Pair("titlereverse", "Z-A"),
        Pair("update", "جديد إصداراتنا"),
        Pair("latest", "تاريخ الإضافة"),
        Pair("popular", "الشهرة")
    )

    private fun getStatusFilters(): Array<Pair<String?, String>> = arrayOf(
        Pair("", "All"),
        Pair("ongoing", "Ongoing"),
        Pair("completed", "Completed"),
        Pair("hiatus", "Hiatus")

    )

    private fun getTypeFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("", "All"),
        Pair("Manga", "Manga"),
        Pair("Manhwa", "Manhwa"),
        Pair("Manhua", "Manhua"),
        Pair("Comic", "Comic"),
        Pair("Novel", "Novel"),
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
