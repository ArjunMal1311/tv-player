package dev.atg.tvplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

// Opens a web page out of sight, finds the site's own video player and shows only that player,
// fullscreen, with its own controls and subtitles. The rest of the page is never shown.
// Remote keys go to the player as keyboard keys (OK = Space, arrows = arrows). Send with:
//   am start -n dev.atg.tvplayer/.WebPlayerActivity -a android.intent.action.VIEW -d '<page url>'
class WebPlayerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cover: TextView
    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var noVideoPolls = 0
    private var iframeHops = 0
    private var found = false
    private var pageHost: String? = null
    private var lastPoll: String? = null
    private lateinit var adHosts: List<String>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adHosts = assets.open("adhosts.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                    if (isAd(req.url.host ?: "", req.url.toString())) EMPTY() else null

                // Ad clicks on these sites redirect the whole page; only the page we opened may load.
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                    req.isForMainFrame && !req.isRedirect && req.url.host != pageHost
            }
            // Pop-up windows (ads) are never opened.
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                                            resultMsg: android.os.Message): Boolean = false
            }
        }

        cover = TextView(this).apply {
            setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE); textSize = 28f
            setPadding(64, 64, 64, 64); text = "Loading…"
        }
        val root = FrameLayout(this)
        root.addView(web)
        root.addView(cover)
        setContentView(root)
        load(intent.dataString)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Debug: am start -n dev.atg.tvplayer/.WebPlayerActivity --es js '<code>'  (result in logcat, tag tvp)
        intent.getStringExtra("js")?.let { js ->
            web.evaluateJavascript(js) { android.util.Log.i("tvp", "js -> $it") }
            return
        }
        setIntent(intent)
        load(intent.dataString)
    }

    private fun load(url: String?) {
        if (url == null) { cover.text = "Send a link: tv <url>"; return }
        main.removeCallbacksAndMessages(null)
        found = false; buttonMode = false; noVideoPolls = 0; iframeHops = 0
        startedAt = System.currentTimeMillis()
        cover.text = "Loading…"; cover.visibility = View.VISIBLE
        open(url)
        main.postDelayed(::poll, 1500)
    }

    private fun poll() {
        web.evaluateJavascript(ISOLATE_JS) { raw ->
            if (raw != lastPoll) { lastPoll = raw; android.util.Log.i("tvp", "$raw  ${web.url}") }
            val r = raw?.trim('"') ?: "none"
            when {
                r == "found" -> {
                    if (!found) { found = true; cover.visibility = View.GONE; web.requestFocus() }
                }
                r.startsWith("iframe:") && noVideoPolls >= 3 && iframeHops < 2 -> {
                    iframeHops++; noVideoPolls = 0
                    open(r.removePrefix("iframe:").replace("\\/", "/"))
                }
                else -> {
                    noVideoPolls++
                    if (!found && System.currentTimeMillis() - startedAt > NO_VIDEO_MS) {
                        cover.text = "Still looking for the video…"
                    }
                }
            }
            main.postDelayed(::poll, 500)
        }
    }

    private fun open(url: String) {
        pageHost = android.net.Uri.parse(url).host
        web.loadUrl(url)
    }

    private fun isAd(host: String, url: String): Boolean = adHosts.any { h ->
        if (h.contains('/')) url.contains(h) else host == h || host.endsWith(".$h")
    }

    // Button mode (Down to enter, Back to leave): arrows move a highlight between the player's
    // own buttons and menu items, OK presses the highlighted one.
    private var buttonMode = false
    private var lastSeek = 0L
    private val leaveButtons = Runnable { setButtonMode(false) }

    private fun setButtonMode(on: Boolean) {
        buttonMode = on
        nav(if (on) "start" else "stop")
        main.removeCallbacks(leaveButtons)
        if (on) {
            main.postDelayed(leaveButtons, BUTTON_IDLE_MS)
            // no buttons to move between: leave, so OK and the arrows pause and skip again
            main.postDelayed({
                web.evaluateJavascript("($NAV_JS)('probe')") { if (buttonMode && it == "\"none\"") setButtonMode(false) }
            }, 1500)
        }
    }

    private fun nav(cmd: String) = web.evaluateJavascript("($NAV_JS)('$cmd')") { r ->
        android.util.Log.i("tvp", "nav $cmd -> $r")
        val at = r?.trim('"')?.takeIf { it.startsWith("at:") }?.removePrefix("at:")?.split(",")
        if (at?.size == 2) tap(at[0].toFloat(), at[1].toFloat())
    }

    // A real touch on the WebView (trusted by the page), at view pixels x, y.
    private fun tap(x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val up = android.view.MotionEvent.obtain(t, t + 80, android.view.MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(up)
            up.recycle()
        }, 80)
    }

    private fun buttonKey(event: KeyEvent): Boolean {
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "click"
            KeyEvent.KEYCODE_BACK -> "back"
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (cmd == "back") setButtonMode(false)
            else {
                nav(cmd)
                main.removeCallbacks(leaveButtons)
                main.postDelayed(leaveButtons, BUTTON_IDLE_MS)
            }
        }
        return true
    }

    // Remote keys outside button mode: OK plays/pauses, Left/Right skip 10 s, Up wakes the controls.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (found && buttonMode && buttonKey(event)) return true
        if (found && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) setButtonMode(true)
            return true
        }
        val action = if (found) KEY_ACTIONS[event.keyCode] else null
        if (action == null) return super.dispatchKeyEvent(event)
        // Left/Right always skip 10 s on the video itself (players differ in what arrows do);
        // holding the key keeps skipping, one step per SEEK_REPEAT_MS. The player's controls wake up.
        if (action == "fwd" || action == "back") {
            val now = android.os.SystemClock.uptimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || now - lastSeek >= SEEK_REPEAT_MS)) {
                lastSeek = now
                val step = if (action == "fwd") "+10" else "-10"
                web.evaluateJavascript(SNAP_JS, null)
                web.evaluateJavascript("(function(){var v=window.__tvpV;if(v)v.currentTime=Math.max(0,v.currentTime$step)})()", null)
            }
            return true
        }
        // OK plays/pauses through the player itself (JW Player's API if there is one, else the
        // video), so the player's own idea of paused/playing never disagrees with ours.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            web.evaluateJavascript(SNAP_JS, null)
            if (action == "toggle") web.evaluateJavascript(TOGGLE_JS, null)
        }
        return true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val NO_VIDEO_MS = 15_000L
        val KEY_ACTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to "toggle", KeyEvent.KEYCODE_ENTER to "toggle",
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "toggle", KeyEvent.KEYCODE_SPACE to "toggle",
            KeyEvent.KEYCODE_DPAD_RIGHT to "fwd", KeyEvent.KEYCODE_DPAD_LEFT to "back",
            KeyEvent.KEYCODE_DPAD_UP to "show")
        const val BUTTON_IDLE_MS = 10_000L
        const val SEEK_REPEAT_MS = 250L

        // Button mode in the page: "start" | "up" | "down" | "left" | "right" | "click" | "stop".
        // Candidates are the visible clickable things inside the player box (outermost only);
        // a yellow ring marks the current one.
        val NAV_JS = """
(function(cmd){
  var box=window.__tvpBox||document.body,r0=box.getBoundingClientRect(),j=Date.now()%9;
  var ring=document.getElementById('__tvp_ring');
  if(cmd==='stop'){if(ring)ring.remove();window.__tvpCur=null;
    // let the player's controls fade out again: JW Player hides them behind this flag
    if(box.classList&&box.classList.contains('jwplayer'))box.classList.add('jw-flag-user-inactive');
    box.dispatchEvent(new MouseEvent('mouseleave'));box.dispatchEvent(new MouseEvent('mouseout',{bubbles:true}));
    return 'off';}
  ['pointermove','mousemove'].forEach(function(t){box.dispatchEvent(new MouseEvent(t,{bubbles:true,clientX:r0.left+r0.width/2+j,clientY:r0.top+r0.height/2+j}))});
  // JW Player hides its controls behind this flag until the mouse moves
  if(box.classList)box.classList.remove('jw-flag-user-inactive');
  // inside a scrolling list (a long captions or quality menu): off-screen items still count
  function inScroller(e){for(var a=e.parentElement;a&&a!==document.body;a=a.parentElement){var cs=getComputedStyle(a);
    if(/auto|scroll/.test(cs.overflowY+' '+cs.overflowX)&&(a.scrollHeight>a.clientHeight+2||a.scrollWidth>a.clientWidth+2))return true;}return false;}
  function shown(e){var r=e.getBoundingClientRect();if(r.width<10||r.height<10)return false;
    if((r.bottom<=0||r.right<=0||r.top>=innerHeight||r.left>=innerWidth)&&!inScroller(e))return false;
    if(r.width*r.height>innerWidth*innerHeight*0.5||r.width>innerWidth*0.6)return false;
    for(var a=e;a&&a!==document.documentElement;a=a.parentElement){var cs=getComputedStyle(a);if(cs.display==='none'||parseFloat(cs.opacity)<0.05)return false;}
    return true;}
  function items(){
    var c=[].slice.call(box.querySelectorAll('button,[role=button],[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=option],[role=tab],a[href],[onclick],[tabindex]'));
    [].slice.call(box.querySelectorAll('div,span,li,svg,img')).forEach(function(e){if(getComputedStyle(e).cursor==='pointer')c.push(e)});
    var set=new Set(c.filter(function(e){return e.id!=='__tvp_ring'&&shown(e)}));
    return Array.from(set).filter(function(e){for(var a=e.parentElement;a&&a!==box;a=a.parentElement)if(set.has(a))return false;return true;});
  }
  function ctr(e){var r=e.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2};}
  function mark(e){try{e.scrollIntoView({block:'nearest',inline:'nearest'})}catch(x){}
    window.__tvpCur=e;window.__tvpCurPt=ctr(e);
    if(!ring){ring=document.createElement('div');ring.id='__tvp_ring';box.appendChild(ring);}
    var r=e.getBoundingClientRect(),css={position:'fixed',left:(r.left-4)+'px',top:(r.top-4)+'px',width:(r.width+8)+'px',
      height:(r.height+8)+'px',border:'4px solid #ffcc00','border-radius':'8px','box-sizing':'border-box','pointer-events':'none',
      'z-index':'2147483647',visibility:'visible',display:'block',margin:'0',background:'rgba(255,204,0,0.15)'};
    for(var k in css)ring.style.setProperty(k,css[k],'important');}
  function nearest(p,l){var b=null,d=1e12;l.forEach(function(e){var c=ctr(e),dd=Math.hypot(c.x-p.x,c.y-p.y);if(dd<d){d=dd;b=e;}});return b;}
  if(cmd==='probe')return items().length?'ok':'none';
  function work(){
  var list=items(),cur=window.__tvpCur;
  if(!list.length){if(ring)ring.remove();return 'none';}
  if(cmd==='start'||!cur||list.indexOf(cur)<0){
    cur=nearest(cmd==='start'||!window.__tvpCurPt?{x:0,y:innerHeight}:window.__tvpCurPt,list);
    if(cmd!=='click'){mark(cur);return 'ok';}
  }
  if(cmd==='click'){
    var c=ctr(cur);
    // a menu may have opened or closed: stay on the same button, else the nearest one
    setTimeout(function(){var l=items();if(!l.length)return;mark(l.indexOf(cur)>=0?cur:nearest(c,l));},500);
    // the app taps here for real: players ignore clicks made by script
    return 'at:'+Math.round(c.x*devicePixelRatio)+','+Math.round(c.y*devicePixelRatio);
  }
  var p=ctr(cur),best=null,bd=1e12;
  list.forEach(function(e){if(e===cur)return;var q=ctr(e),dx=q.x-p.x,dy=q.y-p.y,ahead,side;
    if(cmd==='left'){ahead=-dx;side=dy}else if(cmd==='right'){ahead=dx;side=dy}else if(cmd==='up'){ahead=-dy;side=dx}else{ahead=dy;side=dx}
    if(ahead<=4)return;var d=ahead+2*Math.abs(side);if(d<bd){bd=d;best=e;}});
  mark(best||cur);
  return 'ok';
  }
  // the controls may still be fading in after the wake-up mousemove: look again a few times
  var res=work(),n=0;
  if(res==='none')(function retry(){setTimeout(function(){if(work()==='none'&&++n<4)retry();},250)})();
  return res;
})
""".trimIndent()

        const val TOGGLE_JS = "(function(){var v=window.__tvpV;if(!v)return;var jw=null;" +
            "try{jw=typeof window.jwplayer==='function'&&jwplayer();if(jw&&!jw.getState)jw=null;}catch(e){jw=null}" +
            "if(v.paused){v.__userPaused=false;if(jw)jw.play();else v.play();}else{v.__userPaused=true;if(jw)jw.pause();else v.pause();}})()"

        // Before a key: focus the player, wake its controls, remember the video's state.
        const val SNAP_JS = "(function(){var v=window.__tvpV,b=window.__tvpBox;if(!v)return;" +
            "window.__tvpSnap={p:v.paused,t:v.currentTime};" +
            "if(b){if(!b.hasAttribute('tabindex'))b.setAttribute('tabindex','-1');b.focus({preventScroll:true});" +
            "var r=b.getBoundingClientRect();b.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:r.width/2,clientY:r.height/2}));}})()"

        fun EMPTY() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        // Picks the playing (else biggest) <video>, then its player box: the nearest ancestor
        // named like a player, else the highest ancestor about the video's size. Pins that box
        // fullscreen and hides the rest. Returns "found", "pending:<readyState>",
        // "iframe:<src>" (no video here, but a big embed) or "none".
        val ISOLATE_JS = """
(function(){
  var best=null,score=-1;
  document.querySelectorAll('video').forEach(function(v){
    if(!(v.currentSrc||v.src||v.readyState>0))return;
    var r=v.getBoundingClientRect();
    var s=r.width*r.height+(v.readyState>0?1e6:0)+(!v.paused&&v.readyState>2?1e10:0)+(v===window.__tvpV&&v.readyState>0?1e9:0);
    if(s>score){score=s;best=v;}
  });
  if(best){
    if(window.__tvpV!==best||!window.__tvpBox||!window.__tvpBox.isConnected){
      window.__tvpV=best;
      var vr=best.getBoundingClientRect(),va=Math.max(vr.width*vr.height,1),box=best,named=null;
      for(var a=best.parentElement;a&&a!==document.body&&a!==document.documentElement;a=a.parentElement){
        var id=(typeof a.className==='string'?a.className:'')+' '+(a.id||'');
        if(!named&&/jwplayer|video-js|vjs-|plyr|art-video|artplayer|dplayer|shaka|mejs|flowplayer|clappr|media-player|player/i.test(id))named=a;
        var ar=a.getBoundingClientRect();
        if(ar.width*ar.height<=va*1.4)box=a;
      }
      box=named||box;
      if(window.__tvpBox&&window.__tvpBox!==box)window.__tvpBox.removeAttribute('data-tvp');
      window.__tvpBox=box;
      for(var p=box.parentElement;p;p=p.parentElement){
        ['transform','filter','perspective','contain','will-change'].forEach(function(k){p.style.setProperty(k,k=='will-change'?'auto':'none','important')});
      }
      setTimeout(function(){window.dispatchEvent(new Event('resize'))},50);
    }
    var box=window.__tvpBox;
    if(!box.hasAttribute('data-tvp'))box.setAttribute('data-tvp','1');
    if(!document.getElementById('__tvp_css')){
      var st=document.createElement('style');st.id='__tvp_css';
      // hide everything outside the player; inside it the player's own show/hide rules apply
      st.textContent='*:not([data-tvp]):not([data-tvp] *){visibility:hidden!important}html,body{background:#000!important;overflow:hidden!important}'+
        '[data-tvp]{visibility:visible!important}'+
        '[data-tvp]{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;'+
        'max-width:none!important;max-height:none!important;margin:0!important;z-index:2147483647!important;transform:none!important}'+
        '[data-tvp] video{object-fit:contain!important}';
      (document.head||document.documentElement).appendChild(st);
    }
    // Same rules inline too: a site's Content-Security-Policy can block the <style> above.
    var imp=function(e,k,v){e.style.setProperty(k,v,'important')};
    [document.documentElement,document.body].forEach(function(e){if(e){imp(e,'visibility','hidden');imp(e,'background','#000');imp(e,'overflow','hidden')}});
    var bs={visibility:'visible',position:'fixed',top:'0',left:'0',width:'100vw',height:'100vh','max-width':'none',
      'max-height':'none',margin:'0','z-index':'2147483647',transform:'none',background:'#000'};
    for(var k in bs)imp(box,k,bs[k]);
    if(best.muted&&!best.__unmuted){best.muted=false;best.volume=1;best.__unmuted=true;}
    var now=Date.now();
    // nudge it to start, but once it has played, a pause is the viewer's (or the player's) choice
    if(!best.paused&&best.currentTime>0)best.__tvpStarted=true;
    if(best.paused&&!best.__userPaused&&!best.__tvpStarted&&!best.ended&&now-(best.__tvpTry||0)>4000){
      best.__tvpTry=now;var pr=best.play();if(pr&&pr.catch)pr.catch(function(){});
    }
    if(best.readyState>=2||best.currentTime>0)return 'found';
  }
  // Nothing playing yet: start a JW Player, or press one visible Play button (max 8 per page;
  // the same button again after 5 s, in case the page wasn't ready for the first press).
  try{var jw=typeof window.jwplayer==='function'&&jwplayer();
    if(jw&&jw.getState&&/idle|paused/.test(jw.getState())&&!(best&&best.__userPaused)&&Date.now()-(window.__tvpJw||0)>4000){window.__tvpJw=Date.now();jw.play();}}catch(e){}
  // a video that is loading: wait for it. One stuck at readyState 0 may need a Play press or lives in an embed.
  if(best&&best.readyState>0)return 'pending:'+best.readyState;
  window.__tvpClicks=window.__tvpClicks||0;
  // a click must not change which episode the link pointed at (#s2-e7 etc.)
  if(window.__tvpHash===undefined)window.__tvpHash=location.hash;
  else if(location.hash!==window.__tvpHash){location.hash=window.__tvpHash;window.__tvpRestored=Date.now();}
  if(window.__tvpClicks<8){
    var re=/(^|[^a-z])play([^a-z]|$)|play-?btn|watch now|play now|play episode/i;
    var strong=/play episode|play movie|watch now|play now|^\s*play\s*$/i,btn=null,top=-1e12;
    document.querySelectorAll('button,[role=button],a,div').forEach(function(e){
      if(e.__tvpClicked&&Date.now()-e.__tvpClicked<5000)return;
      var r=e.getBoundingClientRect();if(r.width<30||r.height<30||r.width>500||r.height>500)return;
      // a link to another title or episode ("Play S1E1", "Latest S2E6") is not this page's player
      var l=e.closest('a[href]'),ha=l&&l.getAttribute('href');
      if(l&&!/^\s*(#\s*$|javascript:)/i.test(ha)&&l.href!==location.href)return;
      var lab=(e.getAttribute('aria-label')||'')+' '+(e.title||'');
      var cls=e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className;
      if(!re.test(lab+' '+cls+' '+(e.children.length?'':e.textContent)))return;
      var isStrong=strong.test(lab)||strong.test(e.textContent||'');
      // the player sits at the top; episode lists further down also have Play buttons
      if(!isStrong&&r.top+window.scrollY>window.innerHeight*0.8)return;
      var sc=(isStrong?1e8:0)-(r.top+window.scrollY);
      if(sc>top){top=sc;btn=e;}
    });
    if(btn){btn.__tvpClicked=Date.now();window.__tvpClicks++;btn.click();}
  }
  // after putting the episode back, give the site a moment to swap its player
  if(Date.now()-(window.__tvpRestored||0)<3000)return 'none';
  var big=null,area=0;
  document.querySelectorAll('iframe').forEach(function(f){
    var r=f.getBoundingClientRect(),a=r.width*r.height;
    if(/^https?:/.test(f.src)&&a>area){area=a;big=f;}
  });
  return big&&area>200*150?'iframe:'+big.src:best?'pending:0':'none';
})()
""".trimIndent()
    }
}
