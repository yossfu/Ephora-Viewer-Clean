package com.ephora.sl
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.opengl.GLSurfaceView
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
class MainActivity : ComponentActivity() {
  val scope = MainScope()
  var lastOut = "sin login aun"
  var lastUser = ""
  var lastGrid = ""
  var lastStart = ""
  var lastCaps = "sin probar"
  var lastUdp = "sin probar"
  var inWorld = false
  var sesionFlag = "?"
  var mundoT0 = 0L
  var gfxOpenLatch = "?"
  var gfxExitLatch = "?"
  var opening3d = false
  var visStash = mutableListOf<Pair<View,Int>>()
  @Volatile var uiBeatMs = 0L
  var uiBeatH: android.os.Handler? = null
  var uiBeatR: Runnable? = null
  var bgTx0 = 0L
  var refreshUi: (() -> Unit)? = null
  override fun onPause() {
    super.onPause()
    try { findViewById<GLSurfaceView>(R.id.surface3d)?.onPause() } catch(_: Throwable) {}
    try { if (inWorld) bgTx0 = AgentLoop.tx } catch(_: Throwable) {}
  }
  override fun onResume() {
    super.onResume()
    try { findViewById<GLSurfaceView>(R.id.surface3d)?.let { it.onResume(); it.requestRender() } } catch(_: Throwable) {}
    try {
      if (inWorld) {
        val ahora = AgentLoop.tx
        val eqVivo = AgentLoop.running && EventQueue.EQLoop.running && EphoraService.running
        if (!EphoraService.running) inWorld = false
        lastUdp = (lastUdp + "\nBG-OK auTxAntes=" + bgTx0 + " auTxAhora=" + ahora + " eqVivo=" + (if (eqVivo) "si" else "no")).takeLast(4000)
        try { refreshUi?.invoke() } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
  }
  fun vis(v: View): String {
    val s = when (v.visibility) { View.VISIBLE -> "VISIBLE"; View.GONE -> "GONE"; else -> "INVISIBLE" }
    return s + " h=" + v.height
  }
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    setContentView(R.layout.activity_main)
    try {
      val cf = java.io.File(filesDir, "crash-prev.txt")
      if (cf.exists()) {
        val txt = try { cf.readText() } catch(_: Throwable) { "" }
        try { cf.delete() } catch(_: Throwable) {}
        if (txt.isNotBlank()) lastOut = "CRASH-PREVIO\n" + txt.take(3000) + "\n" + lastOut
      }
    } catch(_: Throwable) {}
    try {
      val uf = java.io.File(filesDir, "ui-freeze.txt")
      if (uf.exists()) {
        val txt = try { uf.readText() } catch(_: Throwable) { "" }
        try { uf.delete() } catch(_: Throwable) {}
        if (txt.isNotBlank()) lastUdp = ("UI-FREEZE-PREVIO\n" + txt.take(3000) + "\n" + lastUdp).takeLast(12000)
      }
    } catch(_: Throwable) {}
    try {
      val prevH = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
          val sb = StringBuilder()
          sb.append(e::class.java.name + " msg=" + (e.message ?: "sin-mensaje") + " hilo=" + t.name + "\n")
          for (s in e.stackTrace.take(30)) sb.append("  at " + s.toString() + "\n")
          java.io.File(filesDir, "crash-prev.txt").writeText(sb.toString().take(4000))
        } catch(_: Throwable) {}
        try { prevH?.uncaughtException(t, e) } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
    val drawer = findViewById<DrawerLayout>(R.id.drawer)
    val loginView = findViewById<View>(R.id.loginView)
    val mundo = findViewById<View>(R.id.mundo)
    val userFull = findViewById<EditText>(R.id.userFull)
    val userLopt = findViewById<EditText>(R.id.userLopt)
    val pw = findViewById<EditText>(R.id.pw)
    val chkShow = findViewById<CheckBox>(R.id.chkShow)
    val btnEnter = findViewById<Button>(R.id.btnEnter)
    val prog = findViewById<View>(R.id.prog)
    val phase = findViewById<TextView>(R.id.phase)
    val loginMenu = findViewById<Button>(R.id.loginMenu)
    val btnMenu = findViewById<Button>(R.id.btnMenu)
    val worldPhase = findViewById<TextView>(R.id.worldPhase)
    val joyF = findViewById<Button>(R.id.joyF)
    val joyB = findViewById<Button>(R.id.joyB)
    val joyL = findViewById<Button>(R.id.joyL)
    val joyR = findViewById<Button>(R.id.joyR)
    val chatInput = findViewById<EditText>(R.id.chatInput)
    val btnSend = findViewById<Button>(R.id.btnSend)
    val btnRetry = findViewById<Button>(R.id.btnRetry)
    val btnChats = findViewById<Button>(R.id.btnChats)
    val btn3d = findViewById<Button>(R.id.btn3d)
    val view3d = findViewById<View>(R.id.view3d)
    val surface3d = findViewById<GLSurfaceView>(R.id.surface3d)
    val btn3dExit = findViewById<Button>(R.id.btn3dExit)
    // Register the renderer while the GLSurfaceView is still hidden. If setRenderer()
    // runs only after revealing this SurfaceView, Android may deliver surfaceCreated
    // before GLSurfaceView has a GLThread to receive it; a later activity resume then
    // appears to "fix" the screen by creating a fresh surface.
    var renderer3d: SlWorldRenderer? = SlWorldRenderer(this@MainActivity)
    try { renderer3d?.prepare(surface3d) } catch(_: Throwable) {}
    val streamDevId = try { StreamBridge.devId(this@MainActivity) } catch(_: Throwable) { "nodev" }
    fun snap(): StreamBridge.Snap? {
      return try {
        if (!inWorld) return null
        val edad = ((if (mundoT0 > 0L) ((System.currentTimeMillis() - mundoT0) / 1000L).toString() else "?"))
        val txt = try { lastUdp.takeLast(12000) } catch(_: Throwable) { "" }
        val sum = try { txt.lines().take(3).joinToString("\n")} catch(_: Throwable) { "" }
        StreamBridge.Snap("7.57", try { phase.text.toString() } catch(_: Throwable) { "?" }, edad, txt, sum)
      } catch(_: Throwable) { null }
    }
    fun bootSnap(): StreamBridge.Snap? {
      return try {
        val txt = try { "EPHORA arranque app=7.57 ndk=" + NdkCore.helloSafe() + " device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " grid=" + lastGrid } catch(_: Throwable) { "EPHORA arranque" }
        StreamBridge.Snap("7.57", "arranque", "?", txt, txt)
      } catch(_: Throwable) { null }
    }
    fun snapOrBoot(): StreamBridge.Snap? {
      return try { snap() ?: bootSnap() } catch(_: Throwable) { null }
    }
    fun exit3d() {
      try { for (p in visStash) { try { p.first.visibility = p.second } catch(_: Throwable) {} }; visStash.clear() } catch(_: Throwable) {}
      try { gfxExitLatch = renderer3d?.gfxLine() ?: "?" } catch(_: Throwable) {}
      try { renderer3d?.stop() } catch(_: Throwable) {}
      try { drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED); drawer.setScrimColor(0x99000000.toInt()) } catch(_: Throwable) {}
      try { view3d.visibility = View.GONE } catch(_: Throwable) {}
      try { if (StreamBridge.streaming) scope.launch(Dispatchers.IO) { try { val s = snap(); if (s != null) StreamBridge.pushHist(streamDevId, s) } catch(_: Throwable) {} } } catch(_: Throwable) {}
    }
    val chatListScroll = findViewById<ScrollView>(R.id.chatListScroll)
    val chatList = findViewById<LinearLayout>(R.id.chatList)
    val convBar = findViewById<View>(R.id.convBar)
    val btnBack = findViewById<Button>(R.id.btnBack)
    val convTitle = findViewById<TextView>(R.id.convTitle)
    val inputRow = findViewById<View>(R.id.inputRow)
    val chatScroll = findViewById<ScrollView>(R.id.chatScroll)
    val chatLog = findViewById<TextView>(R.id.chatLog)
    val openChatsBox = findViewById<LinearLayout>(R.id.openChatsBox)
    val onlineBox = findViewById<LinearLayout>(R.id.onlineBox)
    val offlineBox = findViewById<LinearLayout>(R.id.offlineBox)
    val btnEndMundo = findViewById<Button>(R.id.btnEndMundo)
    val btnEndConsola = findViewById<Button>(R.id.btnEndConsola)
    val btnEndReporte = findViewById<Button>(R.id.btnEndReporte)
    val streamDev = findViewById<TextView>(R.id.streamDev)
    val btnStream = findViewById<Button>(R.id.btnStream)
    val btnSubir = findViewById<Button>(R.id.btnSubir)
    var openConv: String? = null
    var uiListaAvisada = false
    var consolaSucia = false
    val console = findViewById<TextView>(R.id.console)
    val btnCopy = findViewById<Button>(R.id.btnCopy)
    val btnShare = findViewById<Button>(R.id.btnShare)
    val btnSave = findViewById<Button>(R.id.btnSave)
    val btnTech = findViewById<Button>(R.id.btnTech)
    val techBox = findViewById<View>(R.id.techBox)
    val techUser = findViewById<TextView>(R.id.techUser)
    val chkHash = findViewById<CheckBox>(R.id.chkHash)
    val start = findViewById<EditText>(R.id.start)
    val mfa = findViewById<EditText>(R.id.mfa)
    val variant = findViewById<Spinner>(R.id.variant)
    val chkOk = findViewById<CheckBox>(R.id.chkOk)
    val btn = findViewById<Button>(R.id.btnLoginReal)
    val btnGet = findViewById<Button>(R.id.btnGet)
    val btnCaps = findViewById<Button>(R.id.btnCaps)
    val btnUdp = findViewById<Button>(R.id.btnUdp)
    variant.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("XML-A (agree 0)", "XML-B (agree 1 + id0)"))
    variant.setSelection(1)
    chkShow.setOnCheckedChangeListener { _, on -> pw.inputType = if (on) 144 else 129 }
    fun hold(b: Button, bit: Int) {
      b.setOnTouchListener { _, ev ->
        when (ev.action) {
          android.view.MotionEvent.ACTION_DOWN -> { AgentLoop.controlFlags = AgentLoop.controlFlags or bit; true }
          android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { AgentLoop.controlFlags = AgentLoop.controlFlags and bit.inv(); true }
          else -> false
        }
      }
    }
    hold(joyF, 1); hold(joyB, 2); hold(joyL, 4); hold(joyR, 8)
    val chatRing = mutableListOf<String>()
    val chatLines = mutableListOf<String>()
    fun convName(id: String): String {
      if (id == "local") return "LOCAL"
      try { ChatManager.buddyNames[id.lowercase()]?.let { return it } } catch(_: Throwable) {}
      try { if (ChatManager.imReplyUuid.equals(id, ignoreCase = true) && ChatManager.imReplyName.isNotBlank()) return ChatManager.imReplyName } catch(_: Throwable) {}
      return id.take(8)
    }
    fun addRow(box: LinearLayout, text: String, open: () -> Unit) {
      try {
        val b = Button(this@MainActivity)
        b.text = text
        b.textSize = 13f
        b.setTextColor(0xFFE8EDF7.toInt())
        try { b.background = getDrawable(R.drawable.btn_dark) } catch(_: Throwable) {}
        b.setOnClickListener { try { open() } catch(_: Throwable) {} }
        box.addView(b)
      } catch(_: Throwable) {}
    }
    fun renderChat() {
      try {
        val id = openConv ?: return
        var title = convName(id)
        if (id != "local") {
          try { if (ChatManager.imTyping.containsKey(id.lowercase())) title += " (escribe...)" } catch(_: Throwable) {}
        }
        convTitle.text = title
        chatLog.text = if (id == "local") {
          if (chatLines.isEmpty()) "(sin mensajes aun)" else chatLines.joinToString("\n")
        } else {
          val h = ChatManager.imThreadGet(id)
          if (h.isEmpty()) "(sin mensajes con " + convName(id) + ")" else h.joinToString("\n")
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
      } catch(_: Throwable) {}
    }
    fun openConversation(id: String) {
      openConv = id
      if (id != "local") {
        ChatManager.imReplyUuid = id
        if (ChatManager.imReplyName.isBlank()) { try { ChatManager.imReplyName = convName(id) } catch(_: Throwable) {} }
        try { ChatManager.imUnreadClear(id) } catch(_: Throwable) {}
      }
      try {
        chatListScroll.visibility = View.GONE
        convBar.visibility = View.VISIBLE
        chatScroll.visibility = View.VISIBLE
        inputRow.visibility = View.VISIBLE
      } catch(_: Throwable) {}
      renderChat()
    }
    fun renderChatList() {
      try {
        if (openConv != null) return
        try { val want = 1 + (try { ChatManager.imThreads.keys.size } catch(_: Throwable) { 0 }); if (want > 0 && chatList.childCount == want) return } catch(_: Throwable) {}
        chatList.removeAllViews()
        val lastLocal = try { chatLines.lastOrNull() ?: "(chat de proximidad)" } catch(_: Throwable) { "" }
        addRow(chatList, "LOCAL\n" + lastLocal.take(80)) { openConversation("local") }
        val keys = try { ChatManager.imThreads.keys.toList() } catch(_: Throwable) { emptyList<String>() }
        for (k in keys) {
          val nm = convName(k)
          val last = try { ChatManager.imThreadGet(k).lastOrNull() ?: "" } catch(_: Throwable) { "" }
          val un = try { ChatManager.imUnread[k.lowercase()] ?: 0 } catch(_: Throwable) { 0 }
          var t = nm + (if (un > 0) " (" + un + ")" else "")
          try { if (ChatManager.imTyping.containsKey(k.lowercase())) t += " \u2014 escribe..." } catch(_: Throwable) {}
          t += "\n" + last.take(80)
          addRow(chatList, t) { openConversation(k) }
        }
      } catch(_: Throwable) {}
    }
    fun renderEndDrawer() {
      try {
        try { val nk = try { ChatManager.imThreads.keys.size } catch(_: Throwable) { 0 }; val ids0 = try { LoginManager.Session.buddies } catch(_: Throwable) { emptyList<String>() }; var nOn = 0; try { for (u in ids0) if (ChatManager.isOnline(u)) nOn++ } catch(_: Throwable) {}; if (openChatsBox.childCount == 1 + nk && onlineBox.childCount == nOn && offlineBox.childCount == ids0.size - nOn) return } catch(_: Throwable) {}
        openChatsBox.removeAllViews()
        onlineBox.removeAllViews()
        offlineBox.removeAllViews()
        addRow(openChatsBox, "LOCAL") { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; openConversation("local") }
        val keys = try { ChatManager.imThreads.keys.toList() } catch(_: Throwable) { emptyList<String>() }
        for (k in keys) addRow(openChatsBox, convName(k)) { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; openConversation(k) }
        val ids = try { LoginManager.Session.buddies.sortedWith(compareBy({ if (ChatManager.isOnline(it)) 0 else 1 }, { (ChatManager.buddyNames[it.lowercase()] ?: it).lowercase() })) } catch(_: Throwable) { emptyList<String>() }
        for (u in ids) {
          val nm = try { ChatManager.buddyNames[u.lowercase()] ?: u.take(8) } catch(_: Throwable) { u.take(8) }
          if (ChatManager.isOnline(u)) addRow(onlineBox, "\u25CF " + nm) { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; openConversation(u) }
          else addRow(offlineBox, nm) { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; openConversation(u) }
        }
      } catch(_: Throwable) {}
    }
    fun showList() {
      openConv = null
      try {
        chatListScroll.visibility = View.VISIBLE
        convBar.visibility = View.GONE
        chatScroll.visibility = View.GONE
        inputRow.visibility = View.GONE
      } catch(_: Throwable) {}
      renderChatList()
      try { renderEndDrawer() } catch(_: Throwable) {}
    }
    fun buildReport(): String {
      val df = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
      return "EPHORA DEBUG "+df.format(Date())+"\napp=7.58\nndk="+NdkCore.helloSafe()+"\ndevice="+Build.MANUFACTURER+" "+Build.MODEL+" sdk="+Build.VERSION.SDK_INT+"\ngrid="+lastGrid+"\nuser="+lastUser.replace("\"","")+"\nstart="+lastStart+"\nsesion="+sesionFlag+" circuitoEdadS="+((if (mundoT0 > 0L) ((System.currentTimeMillis() - mundoT0) / 1000L).toString() else "?"))+"\nfase="+phase.text+"\n---login---\n"+lastOut+"\n---caps---\n"+lastCaps+"\n---udp---\n"+lastUdp+"\nCHAT-RX-ESTADO n="+ChatManager.rxCount+"\nIM-RX-ESTADO n="+ChatManager.imRxCount+"\n---chat---\n"+chatRing.joinToString("\n")+"\nACK-COUNT n="+ChatManager.ackTxTotal+" rxmsg="+ChatManager.ackTotal+"\n"+AgentLoop.nearLine+"\nORACULO "+AgentLoop.sintLine+" | "+AgentLoop.destLine+"\nTHROTTLE-ESTADO "+AgentLoop.throttleLine()+"\nPING-ESTADO tx="+AgentLoop.pingTx+" ultimo="+AgentLoop.lastPingId+"\\n"+ImageAssets.status()+"\\nLOGIN-UI tarjeta userFull="+vis(userFull)+" pw="+vis(pw)+" btnEnter="+vis(btnEnter)+" loginView="+vis(loginView)+" mundo="+vis(mundo)+"\n"+UdpCircuit.rxCountLine()+"STREAM streamDev="+streamDevId+" "+StreamBridge.lastState+"\n---gfx---\n"+(try { renderer3d?.gfxLine() ?: (if (gfxExitLatch != "?") "3D-cerrado " + gfxExitLatch else if (gfxOpenLatch != "?") "3D-cerrado " + gfxOpenLatch else "3D-cerrado") } catch(_: Throwable) { "GFX-DIAG error" })+"\nTERRA nPk="+TerrainMesh.nPk+" patches="+TerrainMesh.patchesGot()+"/256 "+TerrainMesh.coverageStatus()+" min="+TerrainMesh.minH+" max="+TerrainMesh.maxH+"\n"+PrimDecoder.meshStatus()+"\nPRIMS-DEC terse="+PrimDecoder.nTerse+"/"+PrimDecoder.nObjTerse+" comp="+PrimDecoder.nComp+"/"+PrimDecoder.nObjComp+" full="+PrimDecoder.nFull+"/"+PrimDecoder.nObjFull+" cached="+PrimDecoder.nCached+" kill="+PrimDecoder.nKill+"\n"+PrimDecoder.estadoFijo()+"\n"+AgentLoop.descTop()+"\n"+AgentLoop.imgPendiente()+"\n"+AgentLoop.terrenoPend()+"\n"+ImageAssets.pendingTop()+"\n"+PrimDecoder.pubLine()+"\n"+PrimDecoder.censoLine()+"\n"+PrimDecoder.tiposLine()+"\n---fin---"
    }
    fun snapSession(): StreamBridge.SessionSnap? {
      return try {
        val s = LoginManager.Session
        if (s.agentId.isBlank()) return null
        val req = try { Regex("reqId=(\\S+)").findAll(lastOut).lastOrNull()?.groupValues?.getOrNull(1) ?: "?" } catch(_: Throwable) { "?" }
        val head = try { buildReport().lines().take(40).joinToString(" | ") } catch(_: Throwable) { "" }
        StreamBridge.SessionSnap("7.57", req, try { s.agentId.takeLast(4) } catch(_: Throwable) { "?" }, try { s.seedCap.takeLast(4) } catch(_: Throwable) { "?" }, try { CapsManager.capsCount } catch(_: Throwable) { -1 }, try { s.simPort } catch(_: Throwable) { -1 }, head)
      } catch(_: Throwable) { null }
    }

    fun refreshConsole() {
      try { console.text = buildReport().takeLast(12000) } catch(_: Throwable) {}
      try { if (!uiListaAvisada && openConv == null && inWorld && chatList.childCount == 0) { uiListaAvisada = true; lastUdp = (lastUdp + "\nUI-LISTA-VACIA").takeLast(12000) } } catch(_: Throwable) {}
      try {
        val id = openConv
        if (id != null) {
          var title = convName(id)
          if (id != "local" && ChatManager.imTyping.containsKey(id.lowercase())) title += " (escribe...)"
          convTitle.text = title
        }
      } catch(_: Throwable) {}
    }
    fun setPhase(t: String) { phase.text = t; worldPhase.text = t }
    fun updHelp() {
      val p = LoginManager.parseUser(userFull.text.toString(), userLopt.text.toString())
      techUser.text = "efectivo: "+p.first+"."+p.second+" | pwLen="+pw.text.toString().replace("\r","").replace("\n","").length+" | mfa="+(if (mfa.text.toString().filter{it.isDigit()}.length==6) "6dig" else "vacio")+" | hash="+(if (chkHash.isChecked) "lumiya16" else "full")
    }
    fun tpReentry(ip: String, port: Int, seed: String) {
      scope.launch(Dispatchers.IO) {
        try { lastUdp = (lastUdp + "\nTP-REENTRY " + ip + ":" + port + " seedLen=" + seed.length).takeLast(12000) } catch(_: Throwable) {}
        try { AgentLoop.stop() } catch(_: Throwable) {}
        try { EventQueue.EQLoop.stop() } catch(_: Throwable) {}
        try { PrimDecoder.reset() } catch(_: Throwable) {}
        try { TerrainMesh.reset() } catch(_: Throwable) {}
        try { LoginManager.Session.simIp = ip } catch(_: Throwable) {}
        try { LoginManager.Session.simPort = port } catch(_: Throwable) {}
        try { if (seed.isNotBlank()) LoginManager.Session.seedCap = seed } catch(_: Throwable) {}
        try { lastCaps = CapsManager.fetchSeed(LoginManager.Session.seedCap) } catch(_: Throwable) {}
        try { lastUdp = UdpCircuit.handshakeOnce() } catch(_: Throwable) {}
        try {
          val ls = EphoraService.loopScope(scope)
          try { AgentLoop.start(ls) } catch(_: Throwable) {}
          try { lastUdp = (lastUdp + "\n" + EventQueue.EQLoop.start(CapsManager.lastEqUrl, ls)).takeLast(12000) } catch(_: Throwable) {}
        } catch(_: Throwable) {}
        try { lastUdp = (lastUdp + "\nTP-REENTRY-OK").takeLast(12000) } catch(_: Throwable) {}
      }
    }
    fun tpDialog() {
      try {
        val ctx = this@MainActivity
        val lay = android.widget.LinearLayout(ctx)
        lay.orientation = android.widget.LinearLayout.VERTICAL
        val ex = android.widget.EditText(ctx); ex.hint = "grid X (numero)"
        val ey = android.widget.EditText(ctx); ey.hint = "grid Y (numero)"
        val ep = android.widget.EditText(ctx); ep.hint = "pos x,y,z (def 128,128,25)"
        lay.addView(ex); lay.addView(ey); lay.addView(ep)
        val d = android.app.AlertDialog.Builder(ctx).setTitle("Teleport").setView(lay).setPositiveButton("IR", null).setNegativeButton("HOME", null).setNeutralButton("X", null).create()
        d.show()
        try {
          d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
              val gx = ex.text.toString().trim().toLong()
              val gy = ey.text.toString().trim().toLong()
              var lx = 128f; var ly = 128f; var lz = 25f
              try { val c = ep.text.toString().trim().split(","); if (c.size >= 3) { lx = c[0].trim().toFloat(); ly = c[1].trim().toFloat(); lz = c[2].trim().toFloat() } } catch(_: Throwable) {}
              scope.launch(Dispatchers.IO) { try { AgentLoop.sendTeleportHandle(gx, gy, lx, ly, lz) } catch(_: Throwable) {} }
              try { d.dismiss() } catch(_: Throwable) {}
            } catch(_: Throwable) { try { Toast.makeText(ctx, "X/Y numericos", Toast.LENGTH_SHORT).show() } catch(_: Throwable) {} }
          }
        } catch(_: Throwable) {}
        try {
          d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            scope.launch(Dispatchers.IO) { try { AgentLoop.sendTeleportHome() } catch(_: Throwable) {} }
            try { d.dismiss() } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      } catch(_: Throwable) {}
    }
    fun udpLog(line: String) {
      lastUdp = (lastUdp + "\n" + line).takeLast(12000)
      try { if (line.startsWith("TEX-UUID") && !TexFetch.done) TexFetch.kick({ s -> try { udpLog(s) } catch(_: Throwable) {} }) } catch(_: Throwable) {}
      try { if (line.startsWith("TEX-UUID")) scope.launch(Dispatchers.IO) { try { AgentLoop.sendImageReqBody("ui") } catch(_: Throwable) {} } } catch(_: Throwable) {}
      try {
        if (line.startsWith("TP-FINISH")) {
          val ip = line.substringAfter("ip=").substringBefore(" ")
          val port = line.substringAfter("port=").substringBefore(" ").toInt()
          val seed = line.substringAfter("seed=")
          if (ip.isNotBlank() && port > 0 && seed.startsWith("http")) scope.launch(Dispatchers.IO) { try { tpReentry(ip, port, seed) } catch(_: Throwable) {} }
        }
      } catch(_: Throwable) {}
      try {
        if (ChatManager.ringKeep(line)) {
          chatRing.add(line.take(160))
          while (chatRing.size > 600) chatRing.removeAt(0)
        }
      } catch(_: Throwable) {}
      try { consolaSucia = true } catch(_: Throwable) {}
    }
    fun chatLogAdd(line: String) {
      chatLines.add(line)
      while (chatLines.size > 50) chatLines.removeAt(0)
      try {
        if (openConv == "local") chatLog.text = chatLines.joinToString("\n")
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
      } catch(_: Throwable) {}
      try { renderChatList() } catch(_: Throwable) {}
      try { renderEndDrawer() } catch(_: Throwable) {}
    }
    suspend fun enterFlow(): Boolean {
      ChatManager.rxCount = 0
      ChatManager.resetSession()
      chatRing.clear()
      UdpCircuit.resetRx()
      try { TexFetch.reset() } catch(_: Throwable) {}
      try { PrimDecoder.reset() } catch(_: Throwable) {}
      try { MeshAssets.resetSession() } catch(_: Throwable) {}
      try { gfxOpenLatch = "?" } catch(_: Throwable) {}
      try { gfxExitLatch = "?" } catch(_: Throwable) {}
      try { uiBeatMs = System.currentTimeMillis() } catch(_: Throwable) {}
      try { AgentLoop.imgReqSent.clear() } catch(_: Throwable) {}
      try { UdpCircuit.imgReset() } catch(_: Throwable) {}
      lastUser = userFull.text.toString().trim(); lastStart = start.text.toString()
      lastGrid = LoginManager.MAIN
      updHelp()
      setPhase("Conectando...")
      try {
        lastOut = LoginManager.loginOkHttp(userFull.text.toString(), userLopt.text.toString(), pw.text.toString(), lastGrid, lastStart, mfa.text.toString(), "B", "lumiya16")
        refreshConsole()
      } catch(e: Exception) { lastOut = "UI EXC "+e::class.java.simpleName; setPhase("Error de conexion. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      if (LoginManager.Session.agentId.isBlank() || LoginManager.Session.sessionId.isBlank()) { setPhase("Login rechazado. Revisa usuario y password. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      try { udpLog("BUDDY-N " + LoginManager.Session.buddies.size) } catch(_: Throwable) {}
      setPhase("Cargando mundo...")
      try {
        lastCaps = CapsManager.fetchSeed(LoginManager.Session.seedCap)
        refreshConsole()
      } catch(e: Exception) { lastCaps = "CAPS UI EXC "+e::class.java.simpleName; setPhase("Error cargando mundo. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      if (CapsManager.lastEqUrl.isBlank()) { setPhase("Mundo sin EventQueue. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      try {
        lastUdp = UdpCircuit.handshakeOnce()
        refreshConsole()
      } catch(e: Exception) { lastUdp = "UDP UI EXC "+e::class.java.simpleName; setPhase("Error de red UDP. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      if (!lastUdp.contains("handshake=si")) { setPhase("Sin respuesta del sim. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole(); return false }
      try {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41) } catch(_: Throwable) {}
        }
      } catch(_: Throwable) {}
      EphoraService.start(this@MainActivity, lastUser)
      try { sesionFlag = "fresca" } catch(_: Throwable) {}
      try { mundoT0 = System.currentTimeMillis() } catch(_: Throwable) {}
      val ls = EphoraService.loopScope(scope)
      try { MeshAssets.start(ls, CapsManager.meshUrl) } catch(_: Throwable) {}
      AgentLoop.onTick = { line -> scope.launch { udpLog(line) } }
      EventQueue.EQLoop.onTick = { line -> scope.launch { udpLog(line) } }
      AgentLoop.start(ls)
      udpLog(EventQueue.EQLoop.start(CapsManager.lastEqUrl, ls))
      loginView.visibility = View.GONE
      mundo.visibility = View.VISIBLE
      inWorld = true
      try { showList() } catch(_: Throwable) {}
      setPhase("En el mundo")
      try { if (StreamBridge.streaming) scope.launch(Dispatchers.IO) { try { val ss = snapSession(); if (ss != null) StreamBridge.pushSession(streamDevId, ss) } catch(_: Throwable) {} } } catch(_: Throwable) {}
      try { AgentLoop.sendNameReq() } catch(_: Throwable) {}
      try { AgentLoop.sendRetrieve() } catch(_: Throwable) {}
      refreshConsole()
      scope.launch(Dispatchers.IO) {
        try {
          delay(2000L)
          try { if (!AgentLoop.sendNameReq()) udpLog("NAME-REQ-ERROR reintento-2s") } catch(_: Throwable) {}
          try { if (!AgentLoop.sendRetrieve()) udpLog("IM-OFF-DRAIN-ERROR reintento-2s") } catch(_: Throwable) {}
          delay(28000L)
          if (ChatManager.buddyNames.isEmpty() && LoginManager.Session.buddies.isNotEmpty()) {
            try { if (!AgentLoop.sendNameReq()) udpLog("NAME-REQ-ERROR reintento-28s") } catch(_: Throwable) {}
            try { udpLog("NAME-REQ-REINTENTO") } catch(_: Throwable) {}
          }
          if (LoginManager.Session.buddies.isNotEmpty() && ChatManager.imOffSeen == 0) {
            try { AgentLoop.sendRetrieve() } catch(_: Throwable) {}
            try { udpLog("IM-OFF-DRAIN-REINTENTO") } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
      return true
    }
    ChatManager.onChat = { line -> scope.launch { val tM = System.currentTimeMillis(); chatLogAdd(line); udpLog("CHAT-RX-UDP " + line.take(160) + " t=" + tM) } }
    ChatManager.onEqChat = { line -> scope.launch { val tM = System.currentTimeMillis(); chatLogAdd(line); udpLog("CHAT-RX-EQ " + line.take(160) + " t=" + tM) } }
    ChatManager.onEcho = { line -> scope.launch { udpLog(line) } }
    ChatManager.onChatOk = { line -> scope.launch { udpLog(line) } }
    ChatManager.onIm = { line -> scope.launch { try { if (openConv != null && openConv.equals(ChatManager.imReplyUuid, ignoreCase = true)) renderChat() } catch(_: Throwable) {}; try { renderChatList() } catch(_: Throwable) {}; try { renderEndDrawer() } catch(_: Throwable) {}; udpLog("IM-RX " + line.removePrefix("IM de ").take(160) + " t=" + System.currentTimeMillis()) } }
    ChatManager.onTyping = { _, _, _ -> scope.launch { try { if (openConv != null) renderChat() } catch(_: Throwable) {}; try { renderChatList() } catch(_: Throwable) {}; try { renderEndDrawer() } catch(_: Throwable) {}; try { consolaSucia = true } catch(_: Throwable) {} } }
    ChatManager.onDup = { line -> scope.launch { udpLog(line) } }
    ChatManager.onAckTx = { line -> scope.launch { udpLog(line) } }
    btnSend.setOnClickListener {
      val text = chatInput.text.toString().trim()
      if (text.isEmpty()) { udpLog("CHAT-TX-ERROR texto-vacio"); Toast.makeText(this@MainActivity, "texto vacio", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
      val dest = openConv
      if (dest == null) { udpLog("CHAT-TX-ERROR sin-conversacion"); Toast.makeText(this@MainActivity, "abre una conversacion", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
      if (dest != "local") {
        ChatManager.imReplyUuid = dest
        if (ChatManager.imReplyName.isBlank()) { try { ChatManager.imReplyName = convName(dest) } catch(_: Throwable) {} }
        udpLog("IM-DEST " + dest.take(8))
        chatInput.text.clear()
        try { ChatManager.imThreadPush(dest, "IM a " + ChatManager.imReplyName + ": " + text.take(500)) } catch(_: Throwable) {}
        renderChat()
        try { chatInput.post { try { chatInput.requestFocus(); val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; imm.showSoftInput(chatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } catch(_: Throwable) {} } } catch(_: Throwable) {}
        scope.launch(Dispatchers.IO) {
          try {
            try { AgentLoop.sendImTyping(false) } catch(_: Throwable) {}
            val ok = AgentLoop.sendIm(text)
            if (!ok) scope.launch { udpLog("IM-TX-ERROR no-confirmado texto=" + text.take(60)) }
          } catch(e: Exception) { scope.launch { udpLog("IM-TX-ERROR exc=" + e::class.java.simpleName) } }
        }
        return@setOnClickListener
      }
      chatInput.text.clear()
      chatLogAdd("tu: " + text)
      try { chatInput.post { try { chatInput.requestFocus(); val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; imm.showSoftInput(chatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } catch(_: Throwable) {} } } catch(_: Throwable) {}
      if (AgentLoop.loopSock == null) { udpLog("CHAT-TX-ERROR socket-nulo texto=" + text.take(60)); }
      else if (AgentLoop.loopAddr == null) { udpLog("CHAT-TX-ERROR sin-destino texto=" + text.take(60)); }
      scope.launch(Dispatchers.IO) {
        try {
          val tE = text.trim()
          val ok = if (tE == "A-HELLO" || tE == "B-HELLO" || tE == "C-HELLO") AgentLoop.sendExp(tE, tE.substring(0, 1)) else { ChatManager.txVariant = "B"; AgentLoop.sendChat(text) }
          if (!ok) scope.launch { udpLog("CHAT-TX-A-ERROR no-confirmado texto=" + text.take(60)); Toast.makeText(this@MainActivity, "chat no confirmado", Toast.LENGTH_SHORT).show() }
        } catch(e: Exception) { scope.launch { udpLog("CHAT-TX-A-ERROR exc=" + e::class.java.simpleName + " texto=" + text.take(60)); Toast.makeText(this@MainActivity, "chat no confirmado", Toast.LENGTH_SHORT).show() } }
      }
    }
    btnRetry.setOnClickListener {
      scope.launch(Dispatchers.IO) {
        try {
          val ok = if (openConv != null && openConv != "local") AgentLoop.resendIm() else AgentLoop.resendChat()
          if (!ok) scope.launch { Toast.makeText(this@MainActivity, "nada que reenviar", Toast.LENGTH_SHORT).show() }
        } catch(e: Exception) { scope.launch { Toast.makeText(this@MainActivity, "chat no confirmado", Toast.LENGTH_SHORT).show() } }
      }
    }
    btnBack.setOnClickListener { showList() }
    btnChats.setOnClickListener { try { renderEndDrawer() } catch(_: Throwable) {}; try { drawer.openDrawer(Gravity.END) } catch(_: Throwable) {} }
    btnEndMundo.setOnClickListener { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {} }
    btnEndConsola.setOnClickListener { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; try { drawer.openDrawer(Gravity.START) } catch(_: Throwable) {} }
    btn3d.setOnClickListener {
      try {
        if (renderer3d?.isAlive() == true) {
      try { drawer.closeDrawers(); drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED); drawer.setScrimColor(android.graphics.Color.TRANSPARENT) } catch(_: Throwable) {}
      try { view3d.visibility = View.VISIBLE; view3d.bringToFront() } catch(_: Throwable) {}
          udpLog("3D-YA-ABIERTO loop-unico")
          return@setOnClickListener
        }
      } catch(_: Throwable) {}
      try {
        if (opening3d) {
          udpLog("3D-YA-ABRIENDO espera-layout")
          return@setOnClickListener
        }
      } catch(_: Throwable) {}
      try { opening3d = true } catch(_: Throwable) {}
      try {
        drawer.closeDrawers()
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawer.setScrimColor(android.graphics.Color.TRANSPARENT)
        visStash.clear()
        visStash.add(Pair(mundo, mundo.visibility))
        mundo.visibility = View.GONE
        for (v in listOf(chatListScroll, convBar, chatScroll, inputRow)) { visStash.add(Pair(v, v.visibility)); v.visibility = View.GONE }
      } catch(_: Throwable) {}
      try { view3d.visibility = View.VISIBLE; view3d.bringToFront() } catch(_: Throwable) {}
      var supTries = 0
      var forceW = 0
      var forceH = 0
      fun tryOpen3d() {
        var r3d: SlWorldRenderer? = null
        try {
          val sw = try { surface3d.width } catch(_: Throwable) { 0 }
          val sh = try { surface3d.height } catch(_: Throwable) { 0 }
          if (sw <= 1 || sh <= 1) {
            supTries += 1
            if (supTries < 6) {
              try { surface3d.postDelayed({ tryOpen3d() }, 500L) } catch(_: Throwable) {}
              return
            }
            var mw = 1080
            try { mw = resources.displayMetrics.widthPixels } catch(_: Throwable) {}
            var mh2 = 2200
            try { mh2 = resources.displayMetrics.heightPixels } catch(_: Throwable) {}
            if (mw <= 1) mw = 1080
            if (mh2 <= 1) mh2 = 2200
            if (mw <= 1 || mh2 <= 1) {
              try { opening3d = false } catch(_: Throwable) {}
              try { for (p in visStash) { try { p.first.visibility = p.second } catch(_: Throwable) {} }; visStash.clear() } catch(_: Throwable) {}
              try { view3d.visibility = View.GONE } catch(_: Throwable) {}
              udpLog("3D-ABORT-SIN-METRICAS surface=" + sw + "x" + sh)
              return
            }
            udpLog("3D-FALLBACK-METRICS " + mw + "x" + mh2 + " surface=" + sw + "x" + sh)
            try { forceW = mw } catch(_: Throwable) {}
            try { forceH = mh2 } catch(_:Throwable) {}
          }
          val r = renderer3d ?: SlWorldRenderer(this@MainActivity)
          r3d = r
          r.onStats = { line -> try { udpLog(line) } catch(_: Throwable) {} }
          view3d.visibility = View.VISIBLE
          if (forceW > 1 && forceH > 1) { try { r.applyMetrics(forceW, forceH) } catch(_: Throwable) {} }
          if (!r.start(surface3d, forceW, forceH)) throw RuntimeException("3d-init")
          renderer3d = r
          try { opening3d = false } catch(_: Throwable) {}
          try { gfxOpenLatch = r.gfxLine() } catch(_: Throwable) {}
          try { gfxExitLatch = "?" } catch(_: Throwable) {}
          udpLog("3D-ABIERTO")
          try { udpLog(r.sunState() + " surf=" + surface3d.width + "x" + surface3d.height) } catch(_: Throwable) {}
          try { AgentLoop.sintTestOnce() } catch(_: Throwable) {}
          try { udpLog("GFX-OPEN " + gfxOpenLatch) } catch(_: Throwable) {}
        } catch(e: Throwable) {
          try { opening3d = false } catch(_: Throwable) {}
          try { for (p in visStash) { try { p.first.visibility = p.second } catch(_: Throwable) {} }; visStash.clear() } catch(_: Throwable) {}
          renderer3d = null
          try { drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED); drawer.setScrimColor(0x99000000.toInt()) } catch(_: Throwable) {}
          try { view3d.visibility = View.GONE } catch(_: Throwable) {}
          Toast.makeText(this@MainActivity, "3D no disponible", Toast.LENGTH_SHORT).show()
          udpLog("3D-ERROR " + (r3d?.initError ?: ("exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje"))))
        }
      }
      tryOpen3d()
    }
    val btnTp = findViewById<Button>(R.id.btnTp)
    btnTp.setOnClickListener { try { tpDialog() } catch(_: Throwable) {} }
    try {
      chatInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
          try { if (openConv != null && openConv != "local" && (s?.length ?: 0) > 0) scope.launch(Dispatchers.IO) { try { AgentLoop.sendImTyping(true) } catch(_: Throwable) {} } } catch(_: Throwable) {}
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
      })
    } catch(_: Throwable) {}
    btn3dExit.setOnClickListener { exit3d()
      try { opening3d = false } catch(_: Throwable) {}
      try { udpLog("3D-EXIT " + gfxExitLatch) } catch(_: Throwable) {} }
    btnEndReporte.setOnClickListener { try { drawer.closeDrawer(Gravity.END) } catch(_: Throwable) {}; try { val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, buildReport()) }; startActivity(android.content.Intent.createChooser(send, "Compartir reporte")) } catch(_: Throwable) {} }
    try { streamDev.text = "dev " + streamDevId } catch(_: Throwable) {}
    try { btnStream.text = "STREAM: ON" } catch(_: Throwable) {}
    try { StreamBridge.start(scope, streamDevId) { snapOrBoot() } } catch(_: Throwable) {}
    btnStream.setOnClickListener {
      try {
        if (StreamBridge.streaming) { StreamBridge.stop(); btnStream.text = "STREAM: OFF"; try { Toast.makeText(this@MainActivity, "STREAM OFF", Toast.LENGTH_SHORT).show() } catch(_: Throwable) {} }
        else { StreamBridge.start(scope, streamDevId) { snapOrBoot() }; btnStream.text = "STREAM: ON"; try { Toast.makeText(this@MainActivity, "STREAM ON cada 15s", Toast.LENGTH_SHORT).show() } catch(_: Throwable) {} }
      } catch(_: Throwable) {}
    }
    btnSubir.setOnClickListener {
      try { Toast.makeText(this@MainActivity, "subiendo...", Toast.LENGTH_SHORT).show() } catch(_: Throwable) {}
      try { scope.launch(Dispatchers.IO) { try { val s = snap(); if (s != null) StreamBridge.pushNow(streamDevId, s); else StreamBridge.lastState = "STREAM-ESTADO error sin-sesion" } catch(_: Throwable) {}; try { val st = StreamBridge.lastState; runOnUiThread { try { Toast.makeText(this@MainActivity, st, Toast.LENGTH_LONG).show() } catch(_: Throwable) {}; try { streamDev.text = "dev " + streamDevId + " " + st } catch(_: Throwable) {} } } catch(_: Throwable) {} } } catch(_: Throwable) {}
    }
        fun sendTipoUnico(v: String) {
      var base = chatInput.text.toString().trim()
      if (base.isEmpty()) base = "hola"
      var t = base
      if (!t.matches(Regex(".*-T[0-3]$"))) t = base + "-" + v
      chatInput.text.clear()
      chatLogAdd("tu: " + t)
      scope.launch(Dispatchers.IO) {
        try {
          val ok = AgentLoop.sendChatAB(t, v)
          if (!ok) scope.launch { udpLog("CHAT-TX-" + v + "-ERROR no-confirmado texto=" + t.take(60)); Toast.makeText(this@MainActivity, "chat " + v + " no confirmado", Toast.LENGTH_SHORT).show() }
        } catch(e: Exception) { scope.launch { udpLog("CHAT-TX-" + v + "-ERROR exc=" + e::class.java.simpleName + " texto=" + t.take(60)); Toast.makeText(this@MainActivity, "chat " + v + " no confirmado", Toast.LENGTH_SHORT).show() } }
      }
    }
    btnEnter.setOnClickListener {
      btnEnter.isEnabled = false
      prog.visibility = View.VISIBLE
      updHelp()
      scope.launch {
        try { enterFlow() } catch(e: Exception) { setPhase("Error inesperado. Pulsa ENTRAR para reintentar."); btnEnter.text = "REINTENTAR"; refreshConsole() }
        btnEnter.isEnabled = true
        prog.visibility = View.GONE
      }
    }
    fun openDrawer() {
      try { drawer.openDrawer(Gravity.START) } catch(_: Throwable) {}
    }
    btnMenu.setOnClickListener { openDrawer() }
    loginMenu.setOnClickListener { openDrawer() }
    btnTech.setOnClickListener {
      val open = techBox.visibility != View.VISIBLE
      techBox.visibility = if (open) View.VISIBLE else View.GONE
      btnTech.text = if (open) "▼ TÉCNICO (avanzado)" else "▶ TÉCNICO (avanzado)"
    }
    btn.setOnClickListener {
      lastUser = userFull.text.toString().trim(); lastStart = start.text.toString()
      lastGrid = LoginManager.MAIN
      lastOut = "Conectando a "+lastGrid+"..."
      refreshConsole()
      updHelp()
      scope.launch {
        try {
          val v = if (variant.selectedItemPosition == 1) "B" else "A"
          val hm = if (chkHash.isChecked) "lumiya16" else "full"
          lastOut = if (chkOk.isChecked) LoginManager.loginOkHttp(userFull.text.toString(), userLopt.text.toString(), pw.text.toString(), lastGrid, lastStart, mfa.text.toString(), v, hm) else LoginManager.login(userFull.text.toString(), userLopt.text.toString(), pw.text.toString(), lastGrid, lastStart, mfa.text.toString(), v, hm); refreshConsole()
        }
        catch(e: Exception) { lastOut = "UI EXC "+e::class.java.simpleName+" msg="+(e.message ?: "sin-mensaje")+"\n"+android.util.Log.getStackTraceString(e).lines().take(30).joinToString("\n").take(2000); refreshConsole() }
      }
    }
    btnGet.setOnClickListener {
      lastGrid = LoginManager.MAIN
      lastOut = "GET a "+lastGrid+"..."
      refreshConsole()
      scope.launch {
        try { lastOut = LoginManager.controlGet(lastGrid); refreshConsole() }
        catch(e: Exception) { lastOut = "UI EXC "+e::class.java.simpleName; refreshConsole() }
      }
    }
    btnCaps.setOnClickListener {
      lastCaps = "Probando caps..."
      refreshConsole()
      scope.launch {
        try {
          val seed = LoginManager.Session.seedCap
          if (seed.isBlank()) { lastCaps = "CAPS no hay seed: haz LOGIN primero"; refreshConsole(); return@launch }
          val r1 = CapsManager.fetchSeed(seed)
          var r2 = "EQ pendiente (sin EventQueueGet)"
          if (CapsManager.lastEqUrl.isNotBlank()) r2 = EventQueue.pollOnce(CapsManager.lastEqUrl)
          lastCaps = r1 + "\n" + r2; refreshConsole()
        }
        catch(e: Exception) { lastCaps = "CAPS UI EXC "+e::class.java.simpleName; refreshConsole() }
      }
    }
    btnUdp.setOnClickListener {
      scope.launch {
        try {
          if (!AgentLoop.running && !EventQueue.EQLoop.running) {
            lastUdp = "Probando UDP..."
            ChatManager.resetSession()
            ChatManager.rxCount = 0
            refreshConsole()
            lastUdp = UdpCircuit.handshakeOnce(); refreshConsole()
            AgentLoop.onTick = { line -> scope.launch { udpLog(line) } }
            EventQueue.EQLoop.onTick = { line -> scope.launch { udpLog(line) } }
            AgentLoop.start(scope)
            var eqUrl = CapsManager.lastEqUrl
            if (eqUrl.isBlank() && LoginManager.Session.seedCap.isNotBlank()) {
              udpLog(CapsManager.fetchSeed(LoginManager.Session.seedCap))
              eqUrl = CapsManager.lastEqUrl
            }
            udpLog(EventQueue.EQLoop.start(eqUrl, scope))
            btnUdp.text = "DETENER UDP/AU/EQ"
            lastUdp = lastUdp + "\n" + AgentLoop.status() + "\n" + EventQueue.EQLoop.status()
            refreshConsole()
          } else {
            AgentLoop.onTick = null; EventQueue.EQLoop.onTick = null
            AgentLoop.stop(); EventQueue.EQLoop.stop()
            btnUdp.text = "PROBAR UDP (circuito+handshake)"
            lastUdp = lastUdp + "\nSTOP " + AgentLoop.status() + " | " + EventQueue.EQLoop.status()
            refreshConsole()
          }
        }
        catch(e: Exception) { lastUdp = "UDP UI EXC "+e::class.java.simpleName; refreshConsole() }
      }
    }
    try {
      findViewById<Button>(R.id.btnT0).setOnClickListener { sendTipoUnico("T0") }
      findViewById<Button>(R.id.btnT3).setOnClickListener { sendTipoUnico("T3") }
    } catch(_: Throwable) {}
    btnCopy.setOnClickListener {
      val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("ephora", buildReport()))
      Toast.makeText(this, "Reporte copiado", Toast.LENGTH_SHORT).show()
    }
    fun shareReport() {
      try {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, buildReport()) }
        startActivity(android.content.Intent.createChooser(send, "Compartir reporte"))
      } catch(_: Throwable) {}
    }
    btnShare.setOnClickListener { shareReport() }
    btnSave.setOnClickListener {
      try {
        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "ephora-reporte-"+df.format(Date())+".txt"
        val txt = buildReport()
        var path = ""
        if (android.os.Build.VERSION.SDK_INT >= 29) {
          val cv = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name); put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain") }
          val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
          if (uri != null) { contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray()) }; path = "Downloads/"+name }
          else throw RuntimeException("MediaStore null")
        } else {
          val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
          dir.mkdirs()
          val f = File(dir, name); f.writeText(txt); path = f.absolutePath
        }
        lastOut = lastOut+"\n\nTXT guardado en:\n"+path
        refreshConsole()
        Toast.makeText(this, "TXT guardado", Toast.LENGTH_LONG).show()
      } catch(e: Exception) { Toast.makeText(this, "No se pudo guardar: "+e.message, Toast.LENGTH_LONG).show() }
    }
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        try {
          if (drawer.isDrawerOpen(Gravity.START)) { drawer.closeDrawer(Gravity.START); return }
          if (drawer.isDrawerOpen(Gravity.END)) { drawer.closeDrawer(Gravity.END); return }
          if (view3d.visibility == View.VISIBLE) { exit3d(); return }
          if (openConv != null) { showList(); return }
        } catch(_: Throwable) {}
        isEnabled = false
        onBackPressedDispatcher.onBackPressed()
      }
    })
    refreshConsole()
    refreshUi = { try { refreshConsole() } catch(_: Throwable) {} }
    try {
      uiBeatH = android.os.Handler(android.os.Looper.getMainLooper())
      uiBeatMs = System.currentTimeMillis()
      val rb = Runnable {
        try { uiBeatMs = System.currentTimeMillis() } catch(_: Throwable) {}
        try { if (consolaSucia) { consolaSucia = false; refreshConsole() } } catch(_: Throwable) {}
        try { uiBeatH?.postDelayed(uiBeatR!!, 2000L) } catch(_: Throwable) {}
      }
      uiBeatR = rb
      uiBeatH?.postDelayed(rb, 2000L)
    } catch(_: Throwable) {}
    scope.launch(Dispatchers.IO) {
      while (isActive) {
        try { delay(10000L) } catch(_: Throwable) { break }
        try {
          val nowW = System.currentTimeMillis()
          val beatAge = nowW - uiBeatMs
          val r3 = try { renderer3d } catch(_: Throwable) { null }
          val touchAge = try { r3?.touchAgeMs() ?: -1L } catch(_: Throwable) { -1L }
          val frameAge = try { r3?.frameAgeMs() ?: -1L } catch(_: Throwable) { -1L }
          var vwVis = "?"
          try { vwVis = vis(view3d) } catch(_: Throwable) {}
          var drw = "?"
          try { drw = if (drawer.isDrawerOpen(Gravity.START) || drawer.isDrawerOpen(Gravity.END)) "abierto" else "cerrado" } catch(_: Throwable) {}
          var eqRun = false
          try { eqRun = EventQueue.EQLoop.running } catch(_: Throwable) {}
          var agRun = false
          try { agRun = AgentLoop.running } catch(_: Throwable) {}
          var svRun = false
          try { svRun = EphoraService.running } catch(_: Throwable) {}
          val line = "UI-DIAG uiBeatEdadMs=" + beatAge + " view3d=" + vwVis + " drawer=" + drw + " renderer=" + (if (r3 == null) "null" else "vivo") + " service=" + (if (svRun) "si" else "no") + " eq=" + (if (eqRun) "si" else "no") + " agent=" + (if (agRun) "si" else "no") + " inWorld=" + (if (inWorld) "si" else "no") + " fase=" + phase.text + " touchEdadMs=" + touchAge + " frameEdadMs=" + frameAge
          scope.launch { try { udpLog(line) } catch(_: Throwable) {} }
          if (beatAge > 10000L) {
            try { lastUdp = (lastUdp + "\n" + line).takeLast(12000) } catch(_: Throwable) {}
            try { java.io.File(filesDir, "ui-freeze.txt").writeText(line.take(4000)) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
    }
    if (EphoraService.running && LoginManager.Session.agentId.isNotBlank()) {
      inWorld = true
      try { sesionFlag = "reanudada" } catch(_: Throwable) {}
      try { if (mundoT0 == 0L) mundoT0 = System.currentTimeMillis() } catch(_: Throwable) {}
      lastUser = LoginManager.Session.firstLast
      lastGrid = LoginManager.MAIN
      AgentLoop.onTick = { line -> scope.launch { udpLog(line) } }
      EventQueue.EQLoop.onTick = { line -> scope.launch { udpLog(line) } }
      loginView.visibility = View.GONE
      mundo.visibility = View.VISIBLE
      setPhase("En el mundo")
      try { showList() } catch(_: Throwable) {}
      refreshConsole()
    }
    loginView.post {
      try { udpLog("LOGIN-UI-post userFull="+vis(userFull)+" pw="+vis(pw)+" btnEnter="+vis(btnEnter)+" loginView="+vis(loginView)+" mundo="+vis(mundo)) } catch(_: Throwable) {}
    }
  }
  override fun onDestroy() { try { uiBeatH?.removeCallbacks(uiBeatR!!) } catch(_: Throwable) {}; super.onDestroy(); scope.cancel() }
}
