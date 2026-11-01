package com.miiptv.tv

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Cfg(val base: String, val user: String, val pass: String)
data class Item(val id: String, val name: String, val group: String, val url: String, val img: String = "")
data class Episode(val season: String, val num: String, val title: String, val url: String, val id: String = "")
data class Prog(val start: Long, val stop: Long, val title: String)

object Api {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun get(url: String, readTimeout: Int = 40000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = readTimeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun api(cfg: Cfg, action: String = "", extra: String = ""): String {
        var u = cfg.base + "/player_api.php?username=" + enc(cfg.user) + "&password=" + enc(cfg.pass)
        if (action.isNotEmpty()) u += "&action=" + action
        return u + extra
    }

    private fun media(cfg: Cfg, kind: String, id: String, ext: String): String =
        cfg.base + "/" + kind + "/" + enc(cfg.user) + "/" + enc(cfg.pass) + "/" + id + "." + ext

    private fun categories(cfg: Cfg, action: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val arr = JSONArray(get(api(cfg, action)))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.optString("category_id")] = o.optString("category_name")
            }
        } catch (e: Exception) {
            // sin categorías: se muestran todos juntos
        }
        return out
    }

    fun login(cfg: Cfg) {
        val info = JSONObject(get(api(cfg)))
        val ui = info.optJSONObject("user_info") ?: throw Exception("usuario o contraseña incorrectos")
        if (ui.optString("auth") == "0") throw Exception("usuario o contraseña incorrectos")
        val st = ui.optString("status")
        if (st.isNotEmpty() && st != "Active") throw Exception("la cuenta está " + st)
    }

    fun live(cfg: Cfg): List<Item> {
        val names = categories(cfg, "get_live_categories")
        val arr = JSONArray(get(api(cfg, "get_live_streams"), 90000))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            out.add(Item(id, o.optString("name", "Canal"), names[o.optString("category_id")] ?: "",
                media(cfg, "live", id, "m3u8")))
        }
        return out
    }

    fun vod(cfg: Cfg): List<Item> {
        val names = categories(cfg, "get_vod_categories")
        val arr = JSONArray(get(api(cfg, "get_vod_streams"), 90000))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            val ext = o.optString("container_extension").ifEmpty { "mp4" }
            out.add(Item(id, o.optString("name", "Película"), names[o.optString("category_id")] ?: "",
                media(cfg, "movie", id, ext), o.optString("stream_icon")))
        }
        return out
    }

    fun series(cfg: Cfg): List<Item> {
        val names = categories(cfg, "get_series_categories")
        val arr = JSONArray(get(api(cfg, "get_series"), 90000))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Item(o.optString("series_id"), o.optString("name", "Serie"),
                names[o.optString("category_id")] ?: "", "", o.optString("cover")))
        }
        return out
    }

    fun episodes(cfg: Cfg, seriesId: String): List<Episode> {
        val data = JSONObject(get(api(cfg, "get_series_info", "&series_id=" + seriesId), 30000))
        val raw = data.opt("episodes")
        val out = ArrayList<Episode>()

        fun addAll(season: String, arr: JSONArray) {
            val tmp = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) tmp.add(arr.getJSONObject(i))
            tmp.sortBy { it.optString("episode_num").toIntOrNull() ?: 0 }
            for (e in tmp) {
                val num = e.optString("episode_num")
                val ext = e.optString("container_extension").ifEmpty { "mp4" }
                out.add(Episode(
                    e.optString("season").ifEmpty { season },
                    num,
                    e.optString("title").ifEmpty { "Episodio " + num },
                    media(cfg, "series", e.optString("id"), ext),
                    e.optString("id")
                ))
            }
        }

        if (raw is JSONObject) {
            val keys = ArrayList<String>()
            val iter = raw.keys()
            while (iter.hasNext()) keys.add(iter.next())
            keys.sortBy { it.toIntOrNull() ?: 0 }
            for (k in keys) {
                val a = raw.optJSONArray(k)
                if (a != null) addAll(k, a)
            }
        } else if (raw is JSONArray) {
            for (i in 0 until raw.length()) {
                val a = raw.optJSONArray(i)
                if (a != null) addAll((i + 1).toString(), a)
            }
        }
        return out
    }

    fun epg(cfg: Cfg, streamId: String): List<Prog> {
        val data = JSONObject(get(api(cfg, "get_short_epg", "&stream_id=" + streamId + "&limit=5"), 20000))
        val arr = data.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.optString("start_timestamp").toLongOrNull() ?: continue
            val e = o.optString("stop_timestamp").toLongOrNull() ?: continue
            out.add(Prog(s, e, decode(o.optString("title"))))
        }
        return out.sortedBy { it.start }
    }

    private fun decode(t: String): String {
        return try {
            val d = String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (d.isEmpty() || d.contains('\uFFFD')) t else d
        } catch (e: Exception) {
            t
        }
    }
}
