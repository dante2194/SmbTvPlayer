package com.dante.smbtv

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dante.smbtv.databinding.ActivityMainBinding
import com.dante.smbtv.databinding.DialogLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: EntryAdapter

    private val servers = LinkedHashMap<String, Server>()
    private val live = LinkedHashSet<String>()
    private var subs: List<SubFile> = emptyList()

    private var currentUrl: String? = null
    private var scanJob: Job? = null
    private var loadJob: Job? = null
    private var scanning = false
    private var autoDone = false
    private var userActed = false

    private class Loaded(val entries: List<Entry>, val subs: List<SubFile>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Smb.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = EntryAdapter(this, ::onEntryClick, ::onEntryLong)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        b.btnRescan.setOnClickListener {
            userActed = true
            if (currentUrl != null) navigate(null)
            startScan()
        }
        b.btnAdd.setOnClickListener {
            userActed = true
            showAddServer()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cur = currentUrl
                if (cur == null) {
                    finish()
                } else {
                    userActed = true
                    navigate(parentOf(cur), cur)
                }
            }
        })

        Store.savedServers(this).forEach { servers[it] = Server(it, it, true) }
        showServers(null, focus = true)
        servers.keys.toList().forEach { resolveAndRefresh(it) }
        startScan()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            userActed = true
            if (currentUrl != null) navigate(null)
            startScan()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ discovery

    private fun startScan() {
        scanJob?.cancel()
        live.clear()
        scanning = true
        b.progress.visibility = View.VISIBLE
        b.statusText.text = "Scanning your network for SMB servers…"
        if (currentUrl == null) showServers(null, focus = false)

        scanJob = lifecycleScope.launch {
            Discovery.scan(
                onFound = { ip -> onFound(ip) },
                onProgress = { d, t ->
                    b.statusText.text =
                        if (t == 0) "No local network found"
                        else "Scanning…  $d / $t   •   ${live.size} found"
                }
            )
            scanning = false
            b.statusText.text = when (live.size) {
                0 -> "No SMB servers found"
                1 -> "1 SMB server found"
                else -> "${live.size} SMB servers found"
            }
            b.progress.visibility = View.INVISIBLE
            refreshServers()
            maybeAutoOpen(true)
        }
    }

    private fun onFound(ip: String) {
        live.add(ip)
        if (!servers.containsKey(ip)) servers[ip] = Server(ip, ip, false)
        refreshServers()
        maybeAutoOpen(false)
        resolveAndRefresh(ip)
    }

    private fun resolveAndRefresh(ip: String) {
        lifecycleScope.launch {
            val n = Discovery.resolveName(ip)
            val s = servers[ip]
            if (s != null && n != ip && s.name != n) {
                servers[ip] = s.copy(name = n)
                refreshServers()
            }
        }
    }

    /** Opens the last-used server as soon as it shows up, or the only server found. */
    private fun maybeAutoOpen(finished: Boolean) {
        if (autoDone || userActed || currentUrl != null) return
        val last = Store.lastServer(this)
        val target: String = when {
            last != null && live.contains(last) -> last
            finished && live.size == 1 -> live.first()
            finished && live.isEmpty() && servers.size == 1 -> servers.keys.first()
            else -> null
        } ?: return
        autoDone = true
        navigate("smb://$target/")
    }

    // ------------------------------------------------------------ navigation

    private fun refreshServers() {
        if (currentUrl != null) return
        val f = currentFocus
        showServers(null, focus = f == null || f === b.btnRescan)
    }

    private fun showServers(focusUrl: String?, focus: Boolean) {
        loadJob?.cancel()
        currentUrl = null
        b.pathText.text = "Servers on your network"
        val entries = servers.values.map { s ->
            val state = when {
                live.contains(s.host) -> "online"
                s.saved -> "saved, not seen on this scan"
                else -> "found"
            }
            Entry(
                name = if (s.name == s.host) s.host else s.name,
                url = "smb://${s.host}/",
                kind = Kind.SERVER,
                subtitle = "${s.host}   •   $state"
            )
        }
        adapter.submit(entries)
        b.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        b.emptyText.text = if (scanning) {
            "Looking for SMB servers…"
        } else {
            "No SMB servers found.\n\nMake sure SMB sharing is on and the TV is on the same network.\nPress Rescan, or Add server to enter an IP."
        }
        if (focus) focusList(focusUrl)
    }

    private fun navigate(url: String?, focusUrl: String? = null) {
        loadJob?.cancel()
        if (url == null) {
            showServers(focusUrl, focus = true)
            return
        }
        currentUrl = url
        b.pathText.text = pretty(url)
        b.emptyText.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        adapter.submit(emptyList(), reset = true)

        loadJob = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { load(url) }
                subs = res.subs
                adapter.submit(res.entries, reset = true)
                if (res.entries.isEmpty()) {
                    b.emptyText.text = "Nothing playable here.\nPress BACK to go up."
                    b.emptyText.visibility = View.VISIBLE
                }
                Store.setLastServer(this@MainActivity, hostOf(url))
                focusList(focusUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAuthError(e)) promptLogin(url) else showError(url, e)
            } finally {
                if (!scanning) b.progress.visibility = View.INVISIBLE
            }
        }
    }

    private fun load(url: String): Loaded {
        val atHost = parentOf(url) == null
        val files = Smb.list(url)
        val out = ArrayList<Entry>()
        val subList = ArrayList<SubFile>()

        for (f in files) {
            try {
                val name = f.name.trimEnd('/')
                if (name.isEmpty() || name.startsWith(".")) continue
                if (atHost) {
                    if (name.endsWith('$')) continue // hide admin shares (IPC$, C$ …)
                    out.add(Entry(name, f.path, Kind.SHARE, subtitle = "Share"))
                    continue
                }
                if (f.isDirectory) {
                    out.add(Entry(name, f.path, Kind.FOLDER, subtitle = "Folder"))
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in VIDEO_EXT) {
                        val len = f.length()
                        out.add(Entry(name, f.path, Kind.VIDEO, size = len, subtitle = fmtSize(len)))
                    } else if (ext in SUB_EXT) {
                        subList.add(SubFile(name, f.path))
                    }
                }
            } catch (e: Exception) {
                // skip entries we can't stat
            }
        }

        out.sortWith(Comparator { a, c ->
            val ra = if (a.kind == Kind.VIDEO) 1 else 0
            val rc = if (c.kind == Kind.VIDEO) 1 else 0
            if (ra != rc) ra - rc else naturalCompare(a.name, c.name)
        })
        return Loaded(out, subList)
    }

    private fun focusList(url: String?) {
        b.list.post {
            if (adapter.itemCount == 0) {
                b.btnAdd.requestFocus()
                return@post
            }
            val pos = if (url != null) adapter.items.indexOfFirst { it.url == url }.coerceAtLeast(0) else 0
            (b.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
            b.list.post { b.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    private fun pretty(url: String): String {
        val parts = url.removePrefix("smb://").trimEnd('/').split('/').toMutableList()
        servers[parts[0]]?.let { if (it.name != it.host) parts[0] = it.name }
        return parts.joinToString("  ›  ")
    }

    // ------------------------------------------------------------ clicks

    private fun onEntryClick(e: Entry) {
        userActed = true
        when (e.kind) {
            Kind.SERVER, Kind.SHARE, Kind.FOLDER -> navigate(e.url)
            Kind.VIDEO -> play(e)
        }
    }

    private fun onEntryLong(e: Entry): Boolean {
        if (e.kind != Kind.SERVER) return false
        val host = hostOf(e.url)
        AlertDialog.Builder(this)
            .setTitle("Forget ${e.name}?")
            .setMessage("Removes it from your saved servers and deletes its saved login.")
            .setPositiveButton("Forget") { _, _ ->
                Store.removeServer(this, host)
                Smb.invalidate(host)
                if (!live.contains(host)) servers.remove(host)
                refreshServers()
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    private fun play(e: Entry) {
        val vids = adapter.items.filter { it.kind == Kind.VIDEO }
        val idx = vids.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
        Playlist.items = vids.map { PItem(it.name, it.url) }
        Playlist.index = idx
        Playlist.subs = subs
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------ dialogs

    private fun promptLogin(url: String) {
        val host = hostOf(url)
        val d = DialogLoginBinding.inflate(layoutInflater)
        val saved = Store.creds(this, host)
        d.hostLabel.text = "Sign in to $host"
        d.userInput.setText(saved?.user ?: "")
        d.domainInput.setText(saved?.domain ?: "")

        AlertDialog.Builder(this)
            .setView(d.root)
            .setPositiveButton("Connect") { _, _ ->
                Store.saveCreds(
                    this, host,
                    Creds(
                        d.domainInput.text.toString().trim(),
                        d.userInput.text.toString().trim(),
                        d.passInput.text.toString()
                    )
                )
                Smb.invalidate(host)
                navigate(url)
            }
            .setNegativeButton("Cancel") { _, _ -> navigate(parentOf(url)) }
            .setOnCancelListener { navigate(parentOf(url)) }
            .show()
    }

    private fun showAddServer() {
        val input = EditText(this).apply {
            hint = "192.168.1.20  or  hostname"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Add SMB server")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val h = input.text.toString().trim()
                    .removePrefix("smb://").trimEnd('/').substringBefore('/')
                if (h.isNotEmpty()) {
                    Store.addServer(this, h)
                    if (!servers.containsKey(h)) servers[h] = Server(h, h, true)
                    userActed = true
                    navigate("smb://$h/")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(url: String, e: Exception) {
        adapter.submit(emptyList(), reset = true)
        val msg = when {
            e is java.net.UnknownHostException -> "Unknown host."
            e.message?.contains("timed out", true) == true -> "Connection timed out — is the server on?"
            else -> e.message ?: e.javaClass.simpleName
        }
        b.emptyText.text = "Couldn't open ${pretty(url)}\n\n$msg\n\nPress BACK to go up."
        b.emptyText.visibility = View.VISIBLE
    }
}
