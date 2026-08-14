package com.althmany.extractor.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.althmany.extractor.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

object ShizukuBridge {
    const val PERMISSION_REQUEST_CODE=9215
    data class Status(val binderAlive:Boolean,val permissionGranted:Boolean,val serverUid:Int?,val userServiceBound:Boolean){val ready:Boolean get()=binderAlive&&permissionGranted}
    data class FastUiResult(val state:String,val detail:String,val payload:String){val success:Boolean get()=state=="OK"&&payload.startsWith("__AL_FAST_COMPACT__=")}
    data class FastUiFrame(val sequence:Long,val eventTriggered:Boolean,val result:FastUiResult)
    data class ShellResult(val exitCode:Int,val output:String){val success:Boolean get()=exitCode==0}

    @Volatile private var remote:IShizukuShellService?=null
    @Volatile private var binding:CompletableDeferred<Boolean>?=null
    private val lock=Any()
    private val connection=object:ServiceConnection{
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){val c=binder?.takeIf{it.pingBinder()}?.let(IShizukuShellService.Stub::asInterface);remote=c;synchronized(lock){binding?.complete(c!=null);binding=null}}
        override fun onServiceDisconnected(name:ComponentName?){remote=null}
    }

    fun status():Status{val alive=runCatching{Shizuku.pingBinder()}.getOrDefault(false);val granted=alive&&runCatching{Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED}.getOrDefault(false);return Status(alive,granted,if(alive)runCatching{Shizuku.getUid()}.getOrNull() else null,remote?.asBinder()?.pingBinder()==true)}
    fun requestPermission():Boolean{if(!runCatching{Shizuku.pingBinder()}.getOrDefault(false))return false;if(runCatching{Shizuku.checkSelfPermission()}.getOrDefault(PackageManager.PERMISSION_DENIED)==PackageManager.PERMISSION_GRANTED)return true;return runCatching{if(Shizuku.shouldShowRequestPermissionRationale())false else {Shizuku.requestPermission(PERMISSION_REQUEST_CODE);true}}.getOrDefault(false)}

    suspend fun ensureBound(context:Context,timeoutMs:Long=4500):Boolean{val r=remote;if(r!=null&&r.asBinder().pingBinder())return true;if(!status().ready)return false;val d=synchronized(lock){binding?:CompletableDeferred<Boolean>().also{binding=it;val ok=runCatching{Shizuku.bindUserService(userServiceArgs(context.applicationContext),connection);true}.getOrDefault(false);if(!ok){it.complete(false);binding=null}}};return withTimeoutOrNull(timeoutMs){d.await()}?:false}
    suspend fun fastSnapshot(context:Context,targetPackage:String,maxNodes:Int=1200):FastUiResult=withContext(Dispatchers.IO){if(!ensureBound(context))return@withContext FastUiResult("UNAVAILABLE","user service unavailable","");parseFastUi(runCatching{remote?.fastSnapshot(targetPackage,maxNodes)}.getOrNull().orEmpty())}
    suspend fun waitAndSnapshot(context:Context,targetPackage:String,afterSequence:Long,timeoutMs:Int,maxNodes:Int=1200):FastUiFrame=withContext(Dispatchers.IO){if(!ensureBound(context))return@withContext FastUiFrame(afterSequence,false,FastUiResult("UNAVAILABLE","user service unavailable",""));parseFastFrame(runCatching{remote?.waitAndSnapshot(targetPackage,afterSequence,timeoutMs,maxNodes)}.getOrNull().orEmpty(),afterSequence)}
    suspend fun eventSequence(context:Context,targetPackage:String):Long=withContext(Dispatchers.IO){if(!ensureBound(context))0L else runCatching{remote?.fastEventSequence(targetPackage)?:0L}.getOrDefault(0L)}
    suspend fun fastClickNode(context:Context,targetPackage:String,x:Int,y:Int):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastClickNode(targetPackage,x,y)==true}.getOrDefault(false)}
    suspend fun fastTap(context:Context,x:Int,y:Int):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastTap(x,y)==true}.getOrDefault(false)}
    suspend fun fastSwipe(context:Context,sx:Int,sy:Int,ex:Int,ey:Int,durationMs:Int=90):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastSwipe(sx,sy,ex,ey,durationMs)==true}.getOrDefault(false)}
    suspend fun fastBack(context:Context):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastBack()==true}.getOrDefault(false)}
    suspend fun fastSetEditableText(context:Context,targetPackage:String,text:String,preferBottom:Boolean):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastSetEditableText(targetPackage,text,preferBottom)==true}.getOrDefault(false)}
    suspend fun execute(context:Context,command:String,timeoutMs:Int=6000):ShellResult=withContext(Dispatchers.IO){if(!ensureBound(context))return@withContext ShellResult(126,"Shizuku user service unavailable");parseShell(runCatching{remote?.execute(command,timeoutMs)}.getOrNull().orEmpty())}
    suspend fun probe(context:Context,targetPackage:String?):String{val s=status();if(!s.binderAlive)return "binder=OFF";if(!s.permissionGranted)return "binder=ON; permission=DENIED";if(!ensureBound(context))return "binder=ON; permission=GRANTED; userService=FAILED";val id=execute(context,"id",2500);val safe=targetPackage?.takeIf{Regex("[A-Za-z0-9_.]+").matches(it)};val snap=if(safe!=null)fastSnapshot(context,safe,180) else FastUiResult("SKIPPED","no target","");return "binder=ON; permission=GRANTED; serverUid=${s.serverUid?:-1}; shell=${if(id.success)"OK" else "FAIL"}; persistentUI=${snap.state}:${snap.detail.take(80)}"}
    suspend fun launchPackage(context:Context,targetPackage:String):Boolean{if(!Regex("[A-Za-z0-9_.]+").matches(targetPackage))return false;val cmd="monkey -p '$targetPackage' -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1";return execute(context,cmd,4000).success}
    suspend fun reset(context:Context):Boolean=withContext(Dispatchers.IO){ensureBound(context)&&runCatching{remote?.fastResetUiAutomation()==true}.getOrDefault(false)}

    private fun userServiceArgs(context:Context)=Shizuku.UserServiceArgs(ComponentName(context,ShizukuShellUserService::class.java)).daemon(false).processNameSuffix("extractor_shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private fun parseFastUi(raw:String):FastUiResult{val first=raw.lineSequence().firstOrNull().orEmpty();if(!first.startsWith("__AL_FAST_UI__="))return FastUiResult("ERROR",first.take(180),"");val h=first.substringAfter("__AL_FAST_UI__=");return FastUiResult(h.substringBefore(';').uppercase(),h.substringAfter(';',""),raw.substringAfter('\n',""))}
    private fun parseFastFrame(raw:String,fallback:Long):FastUiFrame{val first=raw.lineSequence().firstOrNull().orEmpty();if(!first.startsWith("__AL_FAST_FRAME__="))return FastUiFrame(fallback,false,FastUiResult("ERROR",first.take(180),""));val h=first.substringAfter("__AL_FAST_FRAME__=");val parts=h.split(';');val seq=parts.firstOrNull()?.toLongOrNull()?:fallback;val event=parts.firstOrNull{it.startsWith("event=")}?.substringAfter('=')=="1";val state=parts.firstOrNull{it.startsWith("state=")}?.substringAfter('=')?.uppercase()?:"ERROR";val detail=parts.filterNot{it==parts.firstOrNull()||it.startsWith("event=")||it.startsWith("state=")}.joinToString(";");return FastUiFrame(seq,event,FastUiResult(state,detail,raw.substringAfter('\n',"")))}
    private fun parseShell(raw:String):ShellResult{val first=raw.lineSequence().firstOrNull().orEmpty();return ShellResult(first.substringAfter("__AL_EXIT__=","125").toIntOrNull()?:125,raw.substringAfter('\n',""))}
}
