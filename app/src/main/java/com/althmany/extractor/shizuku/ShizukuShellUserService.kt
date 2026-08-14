package com.althmany.extractor.shizuku

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ShizukuShellUserService : IShizukuShellService.Stub() {
    private val ui=PersistentUiAutomationBridge()
    override fun serviceUid():Int=Process.myUid()
    override fun fastSnapshot(targetPackage:String,maxNodes:Int):String=ui.snapshot(targetPackage,maxNodes)
    override fun fastTap(x:Int,y:Int):Boolean=ui.tap(x,y)
    override fun fastClickNode(targetPackage:String,x:Int,y:Int):Boolean=ui.clickNode(targetPackage,x,y)
    override fun fastSwipe(startX:Int,startY:Int,endX:Int,endY:Int,durationMs:Int):Boolean=ui.swipe(startX,startY,endX,endY,durationMs)
    override fun fastBack():Boolean=ui.back()
    override fun fastSetEditableText(targetPackage:String,text:String,preferBottom:Boolean):Boolean=ui.setEditableText(targetPackage,text,preferBottom)
    override fun fastEventSequence(targetPackage:String):Long=ui.eventSequence(targetPackage)
    override fun waitForFastEvent(targetPackage:String,afterSequence:Long,timeoutMs:Int):Long=ui.waitForEvent(targetPackage,afterSequence,timeoutMs)
    override fun waitAndSnapshot(targetPackage:String,afterSequence:Long,timeoutMs:Int,maxNodes:Int):String=ui.waitAndSnapshot(targetPackage,afterSequence,timeoutMs,maxNodes)
    override fun fastUiStatus():String=ui.status()
    override fun fastResetUiAutomation():Boolean=ui.resetForNewRun()
    override fun execute(command:String,timeoutMs:Int):String{
        val safe=timeoutMs.coerceIn(500,15_000)
        return try{
            val p=ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true).start(); val cap=ByteArrayOutputStream(64*1024)
            val reader=thread(isDaemon=true){val b=ByteArray(8192);p.inputStream.use{input->while(true){val n=input.read(b);if(n<=0)break;val rem=700_000-cap.size();if(rem>0)cap.write(b,0,minOf(n,rem))}}}
            val ok=p.waitFor(safe.toLong(),TimeUnit.MILLISECONDS);if(!ok){p.destroy();if(!p.waitFor(250,TimeUnit.MILLISECONDS))p.destroyForcibly()};reader.join(700)
            "__AL_EXIT__=${if(ok)p.exitValue() else 124}\n${cap.toString(Charsets.UTF_8.name())}".take(700_000)
        }catch(t:Throwable){"__AL_EXIT__=125\n${t.javaClass.simpleName}:${t.message.orEmpty()}"}
    }
}
