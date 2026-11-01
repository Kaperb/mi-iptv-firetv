package com.miiptv.tv

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.graphics.drawable.GradientDrawable
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.media3.common.C
import com.bumptech.glide.Glide
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = Color.parseColor("#0d1117")
private val FIELD = Color.parseColor("#1a222c")
private val FIELD_FOCUS = Color.parseColor("#244a46")
private val LINE = Color.parseColor("#252f3a")
private val HOVER = Color.parseColor("#1f2833")
private val TEXT = Color.parseColor("#e6edf3")
private val MUTED = Color.parseColor("#8b98a5")
private val ACCENT = Color.parseColor("#3ec1b0")
private val ACCENT_H = Color.parseColor("#7ae3d5")
private val ON_ACCENT = Color.parseColor("#06201c")
private const val MAX_ROWS = 3000
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : AppCompatActivity() {

    private enum class State { LOGIN, BROWSE, EPISODES, PLAYER }
    private enum class Mode(val label: String) { LIVE("TV"), MOVIE("Películas"), SERIES("Series") }

    private val ui = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoText.visibility = View.GONE }

    private lateinit var root: FrameLayout
    private lateinit var loginView: LinearLayout
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var loginMsg: TextView

    private lateinit var browseView: LinearLayout
    private lateinit var filterBar: LinearLayout
    private lateinit var btnCat: Button
    private lateinit var etSearch: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private val tabBtns = LinkedHashMap<Mode, Button>()
    private val rowAdapter = RowAdapter()
    private lateinit var grid: GridView
    private val gridAdapter = GridAdapter()
    private val store by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }
    private var currentKey: String? = null
    private var curUrl = ""
    private var altTried = false
    private val saveTick = object : Runnable {
        override fun run() {
            if (state == State.PLAYER && !playingLive) saveProgress()
            ui.postDelayed(this, 10000)
        }
    }

    private lateinit var playerLayer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var infoText: TextView
    private lateinit var exo: ExoPlayer

    private var state = State.LOGIN
    private var mode = Mode.LIVE
    private var cfg: Cfg? = null
    private val cache = HashMap<Mode, List<Item>>()
    private var items: List<Item> = emptyList()
    private var visible: List<Item> = emptyList()
    private var category: String? = null
    private var episodes: List<Episode> = emptyList()
    private var episodesOpen = false
    private var seriesName = ""
    private var savedPos = 0
    private var liveList: List<Item> = emptyList()
    private var liveIndex = 0
    private var playingLive = false
    private var infoBase = ""
    private var infoExtra = ""
    private var epgToken = 0

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(BG)
        setContentView(root)
        buildLogin()
        buildBrowse()
        buildPlayer()
        root.addView(loginView, ViewGroup.LayoutParams(MATCH, MATCH))
        root.addView(browseView, ViewGroup.LayoutParams(MATCH, MATCH))
        root.addView(playerLayer, ViewGroup.LayoutParams(MATCH, MATCH))

        val prefs = getSharedPreferences("iptv", MODE_PRIVATE)
        etServer.setText(prefs.getString("server", ""))
        etUser.setText(prefs.getString("user", ""))
        etPass.setText(prefs.getString("pass", ""))
        show(State.LOGIN)
        if (etServer.text.isNotEmpty() && etUser.text.isNotEmpty() && etPass.text.isNotEmpty()) doLogin()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        exo.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        exo.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------ utilidades de interfaz
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun focusBg(normal: Int, focused: Int): StateListDrawable {
        val d = StateListDrawable()
        d.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(focused))
        d.addState(intArrayOf(), ColorDrawable(normal))
        return d
    }

    private fun styleBtn(b: Button, selected: Boolean) {
        b.background = focusBg(if (selected) ACCENT else HOVER, ACCENT_H)
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(ON_ACCENT, if (selected) ON_ACCENT else TEXT)
            )
        )
    }

    private fun newButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        b.setPadding(dp(18), dp(8), dp(18), dp(8))
        b.setOnClickListener { onClick() }
        styleBtn(b, false)
        return b
    }

    private fun newEdit(hintText: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        e.setSingleLine(true)
        e.inputType = type
        e.background = focusBg(FIELD, FIELD_FOCUS)
        e.setPadding(dp(16), dp(12), dp(16), dp(12))
        e.imeOptions = EditorInfo.IME_ACTION_NEXT
        e.setShowSoftInputOnFocus(false)
        e.setOnClickListener { showKeyboard(e) }
        e.setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) hideKeyboard(v) }
        return e
    }

    private fun showKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, 0)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun show(s: State) {
        state = s
        loginView.visibility = if (s == State.LOGIN) View.VISIBLE else View.GONE
        browseView.visibility = if (s == State.BROWSE || s == State.EPISODES) View.VISIBLE else View.GONE
        playerLayer.visibility = if (s == State.PLAYER) View.VISIBLE else View.GONE
        if (s == State.LOGIN) {
            if (etServer.text.isEmpty()) etServer.requestFocus() else btnLogin.requestFocus()
        }
    }

    // ------------------------------------------------------------ pantalla de acceso
    private fun buildLogin() {
        loginView = LinearLayout(this)
        loginView.orientation = LinearLayout.VERTICAL
        loginView.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "Mi IPTV"
        title.setTextColor(ACCENT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
        title.gravity = Gravity.CENTER

        etServer = newEdit("Servidor (http://servidor:puerto)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        etUser = newEdit("Usuario", InputType.TYPE_CLASS_TEXT)
        etPass = newEdit("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etPass.imeOptions = EditorInfo.IME_ACTION_DONE
        etPass.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(etPass)
            doLogin()
            true
        }
        btnLogin = newButton("Entrar") { doLogin() }
        styleBtn(btnLogin, true)

        loginMsg = TextView(this)
        loginMsg.setTextColor(MUTED)
        loginMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        loginMsg.gravity = Gravity.CENTER

        val w = dp(560)
        fun lp(top: Int): LinearLayout.LayoutParams {
            val p = LinearLayout.LayoutParams(w, WRAP)
            p.topMargin = dp(top)
            return p
        }
        loginView.addView(title, lp(0))
        loginView.addView(etServer, lp(28))
        loginView.addView(etUser, lp(12))
        loginView.addView(etPass, lp(12))
        loginView.addView(btnLogin, lp(20))
        loginView.addView(loginMsg, lp(16))
    }

    private fun errText(e: Exception): String =
        if (e is org.json.JSONException) "respuesta no válida del servidor (¿dirección correcta?)"
        else (e.message ?: e.javaClass.simpleName)

    private fun doLogin() {
        var base = etServer.text.toString().trim().trimEnd('/')
        val u = etUser.text.toString().trim()
        val p = etPass.text.toString()
        if (base.isEmpty() || u.isEmpty() || p.isEmpty()) {
            loginMsg.text = "Completa servidor, usuario y contraseña."
            return
        }
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "http://" + base
        val c = Cfg(base, u, p)
        loginMsg.text = "Conectando…"
        btnLogin.isEnabled = false
        Thread {
            try {
                Api.login(c)
                val live = Api.live(c)
                ui.post { onLoggedIn(c, live) }
            } catch (e: Exception) {
                ui.post {
                    btnLogin.isEnabled = true
                    loginMsg.text = "No se pudo conectar: " + errText(e)
                    show(State.LOGIN)
                }
            }
        }.start()
    }

    private fun onLoggedIn(c: Cfg, live: List<Item>) {
        cfg = c
        getSharedPreferences("iptv", MODE_PRIVATE).edit()
            .putString("server", c.base).putString("user", c.user).putString("pass", c.pass).apply()
        cache.clear()
        cache[Mode.LIVE] = live
        btnLogin.isEnabled = true
        loginMsg.text = ""
        setMode(Mode.LIVE)
    }

    private fun logout() {
        exo.stop()
        cache.clear()
        loginMsg.text = ""
        show(State.LOGIN)
    }

    // ------------------------------------------------------------ explorador
    private inner class RowAdapter : BaseAdapter() {
        private var rows: List<String> = emptyList()

        fun setRows(r: List<String>) {
            rows = r
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t: TextView
            if (convertView is TextView) {
                t = convertView
            } else {
                t = TextView(this@MainActivity)
                t.setTextColor(TEXT)
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                t.setPadding(dp(20), dp(14), dp(20), dp(14))
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
            }
            t.text = rows[position]
            return t
        }
    }

    private inner class GridAdapter : BaseAdapter() {
        private var data: List<Item> = emptyList()

        fun setItems(l: List<Item>) {
            data = l
            notifyDataSetChanged()
        }

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        private fun buildCell(): LinearLayout {
            val ctx = this@MainActivity
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.layoutParams = AbsListView.LayoutParams(MATCH, dp(300))
            cell.setPadding(dp(4), dp(4), dp(4), dp(4))
            val frame = FrameLayout(ctx)
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(HOVER)
            frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 1000
            pb.progressTintList = ColorStateList.valueOf(ACCENT)
            pb.progressBackgroundTintList = ColorStateList.valueOf(LINE)
            frame.addView(pb, FrameLayout.LayoutParams(MATCH, dp(6), Gravity.BOTTOM))
            cell.addView(frame, LinearLayout.LayoutParams(MATCH, dp(236)))
            val tv = TextView(ctx)
            tv.setTextColor(TEXT)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding(dp(2), dp(6), dp(2), 0)
            cell.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
            return cell
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell: LinearLayout = if (convertView is LinearLayout) convertView else buildCell()
            val frame = cell.getChildAt(0) as FrameLayout
            val iv = frame.getChildAt(0) as ImageView
            val pb = frame.getChildAt(1) as ProgressBar
            val tv = cell.getChildAt(1) as TextView
            val item = data[position]
            val watched = mode == Mode.MOVIE && store.getBoolean("w_m_" + item.id, false)
            tv.text = (if (watched) "✓ " else "") + item.name
            if (item.img.startsWith("http")) {
                Glide.with(this@MainActivity).load(item.img).override(dp(200), dp(280)).centerCrop().into(iv)
            } else {
                Glide.with(this@MainActivity).clear(iv)
                iv.setImageDrawable(null)
            }
            val frac = if (mode == Mode.MOVIE) progressOf("m_" + item.id) else 0
            pb.progress = frac
            pb.visibility = if (frac > 0) View.VISIBLE else View.GONE
            return cell
        }
    }

    private fun buildBrowse() {
        browseView = LinearLayout(this)
        browseView.orientation = LinearLayout.VERTICAL
        browseView.setPadding(dp(40), dp(24), dp(40), dp(16))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Mi IPTV"
        title.setTextColor(ACCENT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        status = TextView(this)
        status.setTextColor(MUTED)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        status.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        status.setPadding(dp(16), 0, dp(16), 0)
        val logoutBtn = newButton("Cambiar cuenta") { logout() }
        header.addView(title)
        header.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(logoutBtn)

        filterBar = LinearLayout(this)
        filterBar.orientation = LinearLayout.HORIZONTAL
        filterBar.gravity = Gravity.CENTER_VERTICAL
        for (m in Mode.values()) {
            val b = newButton(m.label) { setMode(m) }
            tabBtns[m] = b
            val p = LinearLayout.LayoutParams(WRAP, WRAP)
            p.rightMargin = dp(8)
            filterBar.addView(b, p)
        }
        btnCat = newButton("Todas las categorías") { pickCategory() }
        val pc = LinearLayout.LayoutParams(WRAP, WRAP)
        pc.rightMargin = dp(8)
        filterBar.addView(btnCat, pc)

        etSearch = newEdit("Buscar", InputType.TYPE_CLASS_TEXT)
        etSearch.imeOptions = EditorInfo.IME_ACTION_DONE
        etSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(etSearch)
            activeView().requestFocus()
            true
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refilter()
            }
        })
        filterBar.addView(etSearch, LinearLayout.LayoutParams(0, WRAP, 1f))

        list = ListView(this)
        list.adapter = rowAdapter
        list.divider = ColorDrawable(LINE)
        list.dividerHeight = 1
        list.setSelector(ColorDrawable(Color.parseColor("#553ec1b0")))
        list.isFocusable = true
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(pos) }

        browseView.addView(header)
        val pf = LinearLayout.LayoutParams(MATCH, WRAP)
        pf.topMargin = dp(12)
        browseView.addView(filterBar, pf)
        val pl = LinearLayout.LayoutParams(MATCH, 0, 1f)
        pl.topMargin = dp(12)
        browseView.addView(list, pl)

        grid = GridView(this)
        grid.adapter = gridAdapter
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dp(170)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = dp(14)
        grid.verticalSpacing = dp(14)
        grid.isFocusable = true
        val sel = GradientDrawable()
        sel.setColor(Color.TRANSPARENT)
        sel.setStroke(dp(4), ACCENT)
        sel.cornerRadius = dp(6).toFloat()
        grid.setSelector(sel)
        grid.setDrawSelectorOnTop(true)
        grid.setOnItemClickListener { _, _, pos, _ -> onRowClick(pos) }
        grid.visibility = View.GONE
        val pg = LinearLayout.LayoutParams(MATCH, 0, 1f)
        pg.topMargin = dp(12)
        browseView.addView(grid, pg)
    }

    private fun setMode(m: Mode) {
        mode = m
        category = null
        episodesOpen = false
        filterBar.visibility = View.VISIBLE
        btnCat.text = "Todas las categorías"
        for ((k, b) in tabBtns) styleBtn(b, k == m)
        show(State.BROWSE)
        applyView()
        etSearch.setText("")
        val cached = cache[m]
        if (cached != null) {
            applyItems(cached)
            activeView().requestFocus()
            return
        }
        applyItems(emptyList())
        val c = cfg ?: return
        status.text = "Cargando " + m.label.lowercase() + "…"
        Thread {
            try {
                val res = if (m == Mode.MOVIE) Api.vod(c) else Api.series(c)
                ui.post {
                    cache[m] = res
                    if (mode == m) {
                        applyItems(res)
                        activeView().requestFocus()
                    }
                }
            } catch (e: Exception) {
                ui.post { if (mode == m) status.text = "No se pudo cargar: " + errText(e) }
            }
        }.start()
    }

    private fun applyItems(l: List<Item>) {
        items = l
        refilter()
    }

    private fun refilter() {
        if (episodesOpen) return
        val q = etSearch.text.toString().trim().lowercase()
        val cat = category
        val match = items.filter {
            (cat == null || it.group == cat) && (q.isEmpty() || it.name.lowercase().contains(q))
        }
        visible = if (match.size > MAX_ROWS) match.subList(0, MAX_ROWS) else match
        rowAdapter.setRows(visible.map { it.name })
        gridAdapter.setItems(visible)
        status.text = if (match.size > MAX_ROWS)
            mode.label + ": " + MAX_ROWS + " de " + match.size + " (usa el buscador)"
        else
            mode.label + ": " + match.size
    }

    private fun pickCategory() {
        val groups = items.map { it.group }.filter { it.isNotEmpty() }.distinct().sorted()
        if (groups.isEmpty()) return
        val labels = (listOf("Todas las categorías") + groups).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Categoría")
            .setItems(labels) { _, which ->
                category = if (which == 0) null else groups[which - 1]
                btnCat.text = labels[which]
                refilter()
                list.requestFocus()
            }
            .show()
    }

    private fun onRowClick(pos: Int) {
        if (episodesOpen) {
            val ep = episodes.getOrNull(pos) ?: return
            playingLive = false
            startMedia(ep.url, seriesName + " · T" + ep.season + " E" + ep.num + " · " + ep.title, false, "e_" + ep.id)
            return
        }
        val item = visible.getOrNull(pos) ?: return
        when (mode) {
            Mode.LIVE -> {
                liveList = visible
                liveIndex = pos
                playLive()
            }
            Mode.MOVIE -> {
                playingLive = false
                startMedia(item.url, item.name, false, "m_" + item.id)
            }
            Mode.SERIES -> openSeries(item)
        }
    }

    private fun openSeries(item: Item) {
        val c = cfg ?: return
        status.text = "Cargando episodios…"
        savedPos = activeView().selectedItemPosition
        Thread {
            try {
                val eps = Api.episodes(c, item.id)
                ui.post { showEpisodes(item, eps) }
            } catch (e: Exception) {
                ui.post { status.text = "No se pudieron cargar los episodios: " + errText(e) }
            }
        }.start()
    }

    private fun showEpisodes(item: Item, eps: List<Episode>) {
        if (eps.isEmpty()) {
            status.text = "Esta serie no tiene episodios disponibles."
            return
        }
        seriesName = item.name
        episodes = eps
        episodesOpen = true
        applyView()
        filterBar.visibility = View.GONE
        rowAdapter.setRows(episodeRows())
        status.text = item.name + " · " + eps.size + " episodios (Atrás para volver)"
        show(State.EPISODES)
        list.requestFocus()
        // se coloca en el primer episodio que aún no has terminado
        val first = eps.indexOfFirst { !store.getBoolean("w_e_" + it.id, false) }
        list.setSelection(if (first < 0) 0 else first)
    }

    private fun closeEpisodes() {
        episodesOpen = false
        applyView()
        filterBar.visibility = View.VISIBLE
        refilter()
        show(State.BROWSE)
        activeView().requestFocus()
        activeView().setSelection(savedPos)
    }

    // ------------------------------------------------------------ vista activa y progreso guardado
    private fun applyView() {
        val useList = mode == Mode.LIVE || episodesOpen
        list.visibility = if (useList) View.VISIBLE else View.GONE
        grid.visibility = if (useList) View.GONE else View.VISIBLE
    }

    private fun activeView(): AbsListView = if (mode == Mode.LIVE || episodesOpen) list else grid

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun progressOf(key: String): Int {
        val p = store.getLong("p_" + key, 0L)
        val d = store.getLong("d_" + key, 0L)
        return if (p > 0L && d > 0L) (p * 1000 / d).toInt().coerceIn(0, 1000) else 0
    }

    private fun saveProgress() {
        val key = currentKey ?: return
        if (playingLive) return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur <= 0L || dur == C.TIME_UNSET) return
        val e = store.edit()
        if (dur - pos < 30000L) {
            e.remove("p_" + key).remove("d_" + key).putBoolean("w_" + key, true)
        } else if (pos > 5000L) {
            e.putLong("p_" + key, pos).putLong("d_" + key, dur)
        }
        e.apply()
    }

    private fun episodeRows(): List<String> = episodes.map { ep ->
        val key = "e_" + ep.id
        var label = "T" + ep.season + " · E" + ep.num + " · " + ep.title
        val pos = store.getLong("p_" + key, 0L)
        if (pos > 0L) label += "   ▶ " + fmtMs(pos)
        else if (store.getBoolean("w_" + key, false)) label += "   ✓"
        label
    }

    // ------------------------------------------------------------ reproductor
    private fun buildPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (playingLive) return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSelected }) {
                    val mime = audio[0].getTrackFormat(0).sampleMimeType ?: "desconocido"
                    infoExtra = "Sin audio: formato no compatible (" + mime.removePrefix("audio/") + ")."
                    showInfo()
                    ui.removeCallbacks(hideInfo)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playingLive && !altTried && curUrl.endsWith(".m3u8") && error.errorCode < 4000) {
                    altTried = true
                    infoExtra = "Probando otro formato…"
                    showInfo()
                    exo.setMediaItem(MediaItem.fromUri(curUrl.removeSuffix(".m3u8") + ".ts"))
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }
                if (error.errorCode in 4000..4999) {
                    infoExtra = "Tu dispositivo no puede reproducir este vídeo (formato 4K/HEVC no compatible). " +
                        "Prueba la versión HD del canal."
                    showInfo()
                    ui.removeCallbacks(hideInfo)
                    return
                }
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                var detail = error.errorCodeName
                if (code != null) detail += ", HTTP " + code
                else if (error.cause?.message != null) detail += ", " + error.cause?.message
                infoExtra = "No se pudo reproducir (" + detail + ")."
                showInfo()
                ui.removeCallbacks(hideInfo)
            }
        })

        playerView = PlayerView(this)
        playerView.player = exo
        playerView.useController = false
        playerView.isFocusable = true
        playerView.setBackgroundColor(Color.BLACK)

        infoText = TextView(this)
        infoText.setTextColor(TEXT)
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        infoText.setBackgroundColor(Color.parseColor("#CC0d1117"))
        infoText.setPadding(dp(24), dp(16), dp(24), dp(16))
        infoText.visibility = View.GONE

        playerLayer = FrameLayout(this)
        playerLayer.setBackgroundColor(Color.BLACK)
        playerLayer.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(40), 0, 0, dp(40))
        playerLayer.addView(infoText, lp)
    }

    private fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))

    private fun showInfo() {
        infoText.text = if (infoExtra.isEmpty()) infoBase else infoBase + "\n" + infoExtra
        infoText.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 6000)
    }

    private fun startMedia(url: String, title: String, live: Boolean, key: String? = null) {
        playerView.useController = !live
        currentKey = if (live) null else key
        curUrl = url
        altTried = false
        val ext = url.substringAfterLast('.', "").uppercase()
        infoBase = if (!live && ext.length in 2..4) title + "  [" + ext + "]" else title
        infoExtra = ""
        show(State.PLAYER)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val resume = if (key != null && !live) store.getLong("p_" + key, 0L) else 0L
        if (resume > 0L) {
            exo.setMediaItem(MediaItem.fromUri(url), resume)
            infoExtra = "Reanudando desde " + fmtMs(resume)
        } else {
            exo.setMediaItem(MediaItem.fromUri(url))
        }
        exo.prepare()
        exo.playWhenReady = true
        playerView.requestFocus()
        showInfo()
        ui.removeCallbacks(saveTick)
        if (!live) ui.postDelayed(saveTick, 10000)
    }

    private fun playLive() {
        val item = liveList.getOrNull(liveIndex) ?: return
        playingLive = true
        startMedia(item.url, (liveIndex + 1).toString() + ". " + item.name, true)
        loadEpg(item)
    }

    private fun zap(dir: Int) {
        if (liveList.isEmpty()) return
        liveIndex = (liveIndex + dir + liveList.size) % liveList.size
        playLive()
    }

    private fun loadEpg(item: Item) {
        val c = cfg ?: return
        epgToken++
        val tok = epgToken
        Thread {
            val progs = try {
                Api.epg(c, item.id)
            } catch (e: Exception) {
                emptyList<Prog>()
            }
            ui.post {
                if (tok == epgToken && state == State.PLAYER) {
                    infoExtra = epgText(progs)
                    showInfo()
                }
            }
        }.start()
    }

    private fun epgText(progs: List<Prog>): String {
        val now = System.currentTimeMillis() / 1000
        val upcoming = progs.filter { it.stop > now }.take(4)
        if (upcoming.isEmpty()) return "Sin guía para este canal"
        return upcoming.joinToString("\n") { p ->
            if (p.start <= now) "Ahora  " + p.title + "  (hasta " + hhmm(p.stop) + ")"
            else hhmm(p.start) + "  " + p.title
        }
    }

    private fun closePlayer() {
        saveProgress()
        ui.removeCallbacks(saveTick)
        exo.stop()
        exo.clearMediaItems()
        ui.removeCallbacks(hideInfo)
        epgToken++
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pos = activeView().selectedItemPosition
        if (episodesOpen) rowAdapter.setRows(episodeRows()) else gridAdapter.notifyDataSetChanged()
        show(if (episodesOpen) State.EPISODES else State.BROWSE)
        activeView().requestFocus()
        if (pos >= 0) activeView().setSelection(pos)
    }

    // ------------------------------------------------------------ mando a distancia
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (state == State.PLAYER && playingLive && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    zap(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    showInfo()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    exo.playWhenReady = !exo.playWhenReady
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }


    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (state) {
            State.PLAYER -> closePlayer()
            State.EPISODES -> closeEpisodes()
            else -> super.onBackPressed()
        }
    }
}
