package com.althmany.extractor.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Shell-owned UiAutomation bridge. It never bypasses Knox/DPC; it can only see UI exposed to Shizuku. */
internal class PersistentUiAutomationBridge {
    @Volatile private var automation: UiAutomation? = null
    @Volatile private var unavailableReason: String? = null
    private val lock = Any()
    private var thread: HandlerThread? = null
    private val eventSequence = AtomicLong(0L)
    private val eventMonitor = java.lang.Object()
    private val packageEventSequence = ConcurrentHashMap<String, Long>()

    fun status(): String = when {
        automation != null -> "READY"
        unavailableReason != null -> "UNAVAILABLE:${unavailableReason.orEmpty().take(160)}"
        else -> "UNKNOWN"
    }

    fun snapshot(targetPackage: String, maxNodes: Int): String {
        val ui = ensureConnected() ?: return marker("UNAVAILABLE", unavailableReason.orEmpty())
        val root = runCatching { ui.rootInActiveWindow }.getOrNull() ?: return marker("NO_ROOT", "rootInActiveWindow=null")
        return try {
            val packed = serializeCompactTree(root, targetPackage, maxNodes.coerceIn(64, 1800))
            marker("OK", "nodes=${packed.second};pkg=${root.packageName?.toString().orEmpty()};format=compact2") + "\n" + packed.first
        } catch (t: Throwable) {
            marker("ERROR", "${t.javaClass.simpleName}:${t.message.orEmpty()}")
        } finally { runCatching { root.recycle() } }
    }

    fun eventSequence(targetPackage: String): Long = packageEventSequence[targetPackage] ?: 0L

    fun waitForEvent(targetPackage: String, afterSequence: Long, timeoutMs: Int): Long {
        ensureConnected() ?: return afterSequence
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1, 600)
        synchronized(eventMonitor) {
            while (true) {
                val current = packageEventSequence[targetPackage] ?: 0L
                if (current > afterSequence) return current
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return afterSequence
                runCatching { eventMonitor.wait(remaining) }
            }
        }
    }

    fun waitAndSnapshot(targetPackage: String, afterSequence: Long, timeoutMs: Int, maxNodes: Int): String {
        ensureConnected() ?: return frameMarker(afterSequence, false, "UNAVAILABLE", unavailableReason.orEmpty())
        val seq = waitForEvent(targetPackage, afterSequence, timeoutMs)
        val triggered = seq > afterSequence
        if (triggered) SystemClock.sleep(12L)
        val root = runCatching { automation?.rootInActiveWindow }.getOrNull() ?: return frameMarker(seq, triggered, "NO_ROOT", "rootInActiveWindow=null")
        return try {
            val packed = serializeCompactTree(root, targetPackage, maxNodes.coerceIn(64, 1800))
            frameMarker(seq, triggered, "OK", "nodes=${packed.second};pkg=${root.packageName?.toString().orEmpty()};format=compact2") + "\n" + packed.first
        } catch (t: Throwable) {
            frameMarker(seq, triggered, "ERROR", "${t.javaClass.simpleName}:${t.message.orEmpty()}")
        } finally { runCatching { root.recycle() } }
    }

    fun tap(x: Int, y: Int): Boolean {
        val ui = ensureConnected() ?: return false
        if (x <= 0 || y <= 0) return false
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(downTime, downTime + 22L, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try { ui.injectInputEvent(down, true) && ui.injectInputEvent(up, true) } catch (_: Throwable) { false } finally { down.recycle(); up.recycle() }
    }

    fun clickNode(targetPackage: String, x: Int, y: Int): Boolean {
        val ui = ensureConnected() ?: return false
        val root = runCatching { ui.rootInActiveWindow }.getOrNull() ?: return false
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var best: AccessibilityNodeInfo? = null; var bestArea = Long.MAX_VALUE
        try {
            while (q.isNotEmpty()) {
                val n=q.removeFirst(); val r=Rect().also(n::getBoundsInScreen)
                if (n.packageName?.toString()==targetPackage && n.isVisibleToUser && n.isEnabled && n.isClickable && r.contains(x,y)) {
                    val area=r.width().toLong().coerceAtLeast(1)*r.height().toLong().coerceAtLeast(1)
                    if (area<bestArea) { best?.recycle(); best=AccessibilityNodeInfo.obtain(n); bestArea=area }
                }
                for(i in 0 until n.childCount.coerceAtMost(80)) n.getChild(i)?.let(q::add)
                if(n!==root) runCatching{n.recycle()}
            }
            return runCatching { best?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true }.getOrDefault(false)
        } finally { best?.let{runCatching{it.recycle()}}; runCatching{root.recycle()} }
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val ui=ensureConnected() ?: return false
        if (minOf(startX,startY,endX,endY)<=0) return false
        val duration=durationMs.coerceIn(18,500); val down=SystemClock.uptimeMillis(); val events=ArrayList<MotionEvent>()
        fun ev(t:Long,a:Int,x:Float,y:Float)=MotionEvent.obtain(down,t,a,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
        return try {
            events+=ev(down,MotionEvent.ACTION_DOWN,startX.toFloat(),startY.toFloat())
            for(i in 1..4){ val f=i/5f; events+=ev(down+duration*i/5,MotionEvent.ACTION_MOVE,startX+(endX-startX)*f,startY+(endY-startY)*f) }
            events+=ev(down+duration,MotionEvent.ACTION_UP,endX.toFloat(),endY.toFloat())
            events.all{ui.injectInputEvent(it,true)}
        } catch(_:Throwable){false} finally{events.forEach{it.recycle()}}
    }

    fun back(): Boolean = runCatching { ensureConnected()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)==true }.getOrDefault(false)

    fun setEditableText(targetPackage: String, text: String, preferBottom: Boolean): Boolean {
        val ui=ensureConnected() ?: return false
        val root=runCatching{ui.rootInActiveWindow}.getOrNull() ?: return false
        val rootBounds=Rect().also(root::getBoundsInScreen); val h=rootBounds.height().coerceAtLeast(1)
        val q=ArrayDeque<AccessibilityNodeInfo>(); q.add(root); val candidates=mutableListOf<AccessibilityNodeInfo>()
        try {
            while(q.isNotEmpty()){
                val n=q.removeFirst()
                if(n.packageName?.toString()==targetPackage && n.isVisibleToUser && n.isEnabled && n.isEditable){ candidates+=AccessibilityNodeInfo.obtain(n) }
                for(i in 0 until n.childCount.coerceAtMost(80)) n.getChild(i)?.let(q::add)
                if(n!==root) runCatching{n.recycle()}
            }
            val chosen=candidates.minByOrNull { n ->
                val r=Rect().also(n::getBoundsInScreen); val center=r.centerY(); val target=if(preferBottom) rootBounds.top+(h*0.82f).toInt() else rootBounds.top+(h*0.16f).toInt(); kotlin.math.abs(center-target)
            } ?: return false
            runCatching{chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)}
            runCatching{chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK)}
            val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}
            return runCatching{chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)}.getOrDefault(false)
        } finally { candidates.forEach{runCatching{it.recycle()}}; runCatching{root.recycle()} }
    }

    fun resetForNewRun(): Boolean {
        synchronized(lock) {
            val current=automation; automation=null
            if(current!=null) runCatching { UiAutomation::class.java.methods.firstOrNull{it.name=="destroy"&&it.parameterCount==0}?.invoke(current) }
            runCatching{thread?.quitSafely()}; thread=null; unavailableReason=null; eventSequence.set(0L); packageEventSequence.clear()
        }
        return ensureConnected()!=null
    }

    private fun ensureConnected(): UiAutomation? {
        automation?.let{return it}; if(unavailableReason!=null)return null
        synchronized(lock){
            automation?.let{return it}; if(unavailableReason!=null)return null
            return try {
                val worker=HandlerThread("althmany-extractor-uia").also{it.start()}; thread=worker
                val connClass=Class.forName("android.app.UiAutomationConnection"); val conn=connClass.getDeclaredConstructor().apply{isAccessible=true}.newInstance()
                val candidate=constructUiAutomation(worker.looper,conn) ?: throw IllegalStateException("UiAutomation constructor unavailable")
                connect(candidate); configure(candidate); automation=candidate; candidate
            } catch(t:Throwable){
                val cause=generateSequence(t){c->when(c){is java.lang.reflect.InvocationTargetException->c.targetException;else->c.cause}}.lastOrNull()?:t
                unavailableReason="${t.javaClass.simpleName}:${cause.javaClass.simpleName}:${cause.message.orEmpty()}"; runCatching{thread?.quitSafely()}; thread=null; null
            }
        }
    }

    private fun constructUiAutomation(looper: Looper, connection: Any): UiAutomation? {
        for(ctor in UiAutomation::class.java.declaredConstructors.sortedBy{it.parameterCount}){
            val t=ctor.parameterTypes; if(t.size !in 2..3 || !Looper::class.java.isAssignableFrom(t[0]) || !t[1].name.contains("IUiAutomationConnection")) continue
            val x=runCatching{ctor.isAccessible=true; if(t.size==2) ctor.newInstance(looper,connection) else ctor.newInstance(looper,connection,0)}.getOrNull(); if(x is UiAutomation)return x
        }; return null
    }
    private fun connect(ui:UiAutomation){
        val methods=UiAutomation::class.java.declaredMethods.filter{it.name=="connect"}; val m0=methods.firstOrNull{it.parameterCount==0}; val m1=methods.firstOrNull{it.parameterCount==1&&it.parameterTypes[0]==Int::class.javaPrimitiveType}
        when{m0!=null->{m0.isAccessible=true;m0.invoke(ui)};m1!=null->{m1.isAccessible=true;m1.invoke(ui,0)};else->throw NoSuchMethodException("UiAutomation.connect")}
    }
    private fun configure(ui:UiAutomation){
        runCatching{
            ui.serviceInfo=AccessibilityServiceInfo().apply{feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;notificationTimeout=0L;flags=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS}
            ui.setOnAccessibilityEventListener{e-> val pkg=e?.packageName?.toString().orEmpty(); if(pkg.isNotBlank()){val seq=eventSequence.incrementAndGet();packageEventSequence[pkg]=seq;synchronized(eventMonitor){eventMonitor.notifyAll()}}}
        }
    }

    private data class Q(val node:AccessibilityNodeInfo,val parent:Int,val depth:Int)
    private fun serializeCompactTree(root:AccessibilityNodeInfo,targetPackage:String,maxNodes:Int):Pair<String,Int>{
        val q=ArrayDeque<Q>();q.add(Q(root,-1,0));val out=StringBuilder(32*1024);out.append("__AL_FAST_COMPACT__=2");var count=0;var serial=0
        while(q.isNotEmpty()&&count<maxNodes&&out.length<420_000){
            val item=q.removeFirst();val n=item.node;val my=serial++
            try{
                val pkg=n.packageName?.toString().orEmpty();val include=pkg.isBlank()||pkg==targetPackage
                if(include){val r=Rect().also(n::getBoundsInScreen);out.append('\n').append("N\t").append(my).append('\t').append(item.parent).append('\t').append(item.depth).append('\t');field(out,n.text?.toString().orEmpty());out.append('\t');field(out,n.contentDescription?.toString().orEmpty());out.append('\t');field(out,n.viewIdResourceName.orEmpty());out.append('\t');field(out,n.className?.toString().orEmpty());out.append('\t');field(out,pkg);out.append('\t').append(if(n.isClickable)1 else 0).append('\t').append(if(n.isEnabled)1 else 0).append('\t').append(if(n.isScrollable)1 else 0).append('\t').append(if(n.isEditable)1 else 0).append('\t').append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom);count++}
                for(i in 0 until n.childCount.coerceAtMost(80)) n.getChild(i)?.let{q.add(Q(it,my,item.depth+1))}
            } finally { if(n!==root)runCatching{n.recycle()} }
        }
        while(q.isNotEmpty())runCatching{q.removeFirst().node.recycle()}; return out.toString() to count
    }
    private fun field(out:StringBuilder,v:String){v.forEach{c->when(c){'\\'->out.append("\\\\");'\t'->out.append("\\t");'\n'->out.append("\\n");'\r'->out.append("\\r");else->if(c>=' ')out.append(c)}}}
    private fun frameMarker(seq:Long,event:Boolean,state:String,detail:String)="__AL_FAST_FRAME__=$seq;event=${if(event)1 else 0};state=$state;$detail"
    private fun marker(state:String,detail:String)="__AL_FAST_UI__=$state;$detail"
}
