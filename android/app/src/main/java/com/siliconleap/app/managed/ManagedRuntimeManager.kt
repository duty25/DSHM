package com.siliconleap.app.managed

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.SubsystemManager
import com.siliconleap.app.runtime.SubsystemPhase
import com.siliconleap.app.runtime.TermuxEnv
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Preview-only controller: does not bootstrap, replace or import the old DSH SDK. */
object ManagedRuntimeManager {
    const val PORT = 39017
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val message = MutableStateFlow("导入受管运行时后启动；当前正式 App 不受影响")
    val status = message.asStateFlow()
    @Volatile private var child: Process? = null
    @Volatile private var boot: Job? = null
    @Volatile private var harnessUrl: String? = null
    private val logLock = Any()
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,95}")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private fun stateDir(c: Context) = File(c.filesDir, "managed-runtime").apply { mkdirs() }
    private fun slots(c: Context) = File(SubsystemManager.rootfsDir(c), "opt/dshm/runtimes").apply { mkdirs() }
    private fun data(c: Context) = File(c.filesDir, "managed-data").apply { mkdirs() }
    private fun user(c: Context) = File(c.filesDir, "managed-user").apply { mkdirs() }
    private fun temp(c: Context) = File(c.filesDir, "managed-tmp").apply { mkdirs() }
    private fun log(c: Context) = File(stateDir(c), "server.log")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(file: File): String { val d=MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buf=ByteArray(65536); while(true){val n=input.read(buf);if(n<0)break;d.update(buf,0,n)} };return d.digest().joinToString(""){"%02x".format(it.toInt() and 255)} }
    private fun safe(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\') && !relative.contains('\u0000')) { "不合法的运行时路径" }
        require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "路径越界" }
        val base=root.canonicalFile;val result=File(base,relative).canonicalFile
        require(result.path.startsWith(base.path+File.separator)) { "路径越界" };return result
    }
    private fun validId(id: String): String { require(idPattern.matches(id) && id!="." && id!="..");return id }
    private fun empty() = JSONObject().put("schemaVersion",1).put("active",JSONObject.NULL).put("previous",JSONObject.NULL).put("verified",JSONObject.NULL).put("revision",0)
    private fun selection(c: Context): JSONObject = File(stateDir(c),"selection.json").let { if(it.isFile)JSONObject(it.readText()) else empty() }.also { require(it.getInt("schemaVersion")==1) }
    private fun writeJson(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp-" + UUID.randomUUID())
        try {
            FileOutputStream(tmp).use {
                it.write(json.toString(2).toByteArray())
                it.fd.sync()
            }
            Os.chmod(tmp.path, 384)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Android's public SDK has no O_DIRECTORY; validate the opened descriptor instead.
            val fd = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
            try {
                require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "选择记录的父路径必须为目录" }
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } finally {
            tmp.delete()
        }
    }
    /** OS releases this lock after crash. File existence is never treated as ownership. */
    private fun <T> locked(c: Context, block: () -> T): T = RandomAccessFile(File(stateDir(c),"selection.lock"),"rw").use { file -> file.channel.use { channel ->
        val lock=channel.tryLock() ?: error("另一个运行时操作尚未完成")
        lock.use { block() }
    } }
    private fun manifest(root: File): JSONObject {
        val m=JSONObject(File(root,"manifest.json").readText())
        require(m.getInt("schemaVersion")==1 && m.getString("backend")=="linux-proot" && m.getString("arch")=="arm64")
        require(m.getString("dshVersion")=="0.1.7-rc.1" && m.getString("nodeVersion")=="24.22.0") { "运行时不符合锁定版本" }
        require(m.getString("dataSchema")=="dsh-session-v4") { "需要单独的数据格式迁移" };validId(m.getString("id"))
        return m
    }
    fun slotNames(c: Context): List<String> = slots(c).listFiles()?.filter { it.isDirectory && File(it,"manifest.json").isFile }?.map { it.name }?.sorted() ?: emptyList()
    fun activeName(c: Context): String? = runCatching { selection(c).optJSONObject("active")?.getString("id") }.getOrNull()
    fun privateLog(c: Context): String = runCatching { log(c).readLines().takeLast(100).joinToString("\n").replace(Regex("(\\?token=)[A-Za-z0-9_=-]+"),"$1<隐藏>") }.getOrDefault("")
    private fun note(c: Context, line: String) = synchronized(logLock) { log(c).appendText(line+"\n");Os.chmod(log(c).path,384) }
    private fun operation(block: suspend () -> Unit) { scope.launch { try { gate.withLock { block() } } catch(e: CancellationException) { throw e } catch(e: Exception) { message.value="操作未完成：${e.message ?: e.javaClass.simpleName}" } } }
    fun prepare(c: Context) = operation {
        SubsystemManager.attach(c.applicationContext)
        AppSettings.setRunMode(c,AppSettings.RUN_MODE_CONTAINER)
        copyAssets(c,"managed-bootstrap",File(stateDir(c),"bootstrap"))
        val prefix=TermuxEnv.prefix(c)
        if(!File(prefix,"lib/libtalloc.so.2").isFile){
            message.value="正在安装独立原生引导库…"
            c.assets.open("managed-native-support.zip").use { input ->
                val zip=File(stateDir(c),"native-support.zip");FileOutputStream(zip).use { input.copyTo(it) }
                ZipFile(zip).use { z -> z.entries().asSequence().forEach { entry ->
                    val relative=entry.name.removeSuffix("/");if(relative.isEmpty())return@forEach
                    require(!relative.contains("node_modules/@deepseek-ai/dsh")) { "引导库不得捆绑旧 DSH SDK" }
                    val to=safe(prefix,relative)
                    if(entry.isDirectory)to.mkdirs() else {to.parentFile!!.mkdirs();z.getInputStream(entry).use { src -> FileOutputStream(to).use { src.copyTo(it) } }}
                } };zip.delete()
            }
        }
        message.value="原生引导库就绪；安装 Debian 后导入 Linux 运行时包"
    }
    private fun copyAssets(c: Context, source: String, target: File) {
        target.mkdirs();for(name in c.assets.list(source) ?: emptyArray()){
            val child="$source/$name";val out=File(target,name)
            if(c.assets.list(child)?.isNotEmpty()==true)copyAssets(c,child,out) else c.assets.open(child).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
    }
    fun installRootfs(c: Context) = operation {
        require(child?.isAlive!=true && !ownedNativeAlive(c)) { "先停止预览服务再安装基础环境" }
        require(File(TermuxEnv.prefix(c),"lib/libtalloc.so.2").isFile) { "先准备原生引导库" }
        SubsystemManager.attach(c.applicationContext);SubsystemManager.resetForAutoInstall()
        message.value="正在安装 Debian 基础环境…";SubsystemManager.installAndWait()
        require(SubsystemManager.isInstalled(c)) { "基础环境安装未完成" };message.value="Debian 已就绪"
    }
    fun importBundle(c: Context, uri: Uri) = operation {
        require(child?.isAlive!=true && !ownedNativeAlive(c)) { "导入前请停止预览服务" }
        require(SubsystemManager.isInstalled(c)) { "先安装 Debian 基础环境" }
        val zip=File(stateDir(c),"incoming-"+UUID.randomUUID()+".zip")
        var staging: File?=null
        try {
            message.value="正在读取运行时包…";c.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }
            ZipFile(zip).use { archive ->
                val mf=archive.getEntry("manifest.json") ?: error("缺少 manifest.json")
                require(mf.size in 1L..8_388_608L) { "清单过大" }
                val raw=archive.getInputStream(mf).use { it.readBytes() };val m=JSONObject(String(raw))
                val id=validId(m.getString("id"));val dest=safe(slots(c),id);require(!dest.exists()) { "版本槽已存在，不覆盖；请使用新的构建 ID" }
                val incoming=File(slots(c),"incoming-"+UUID.randomUUID()).apply { mkdirs() };staging=incoming
                File(incoming,"manifest.json").writeBytes(raw);manifest(incoming)
                val declared=linkedMapOf<String,JSONObject>();val files=m.getJSONArray("files")
                for(i in 0 until files.length()){val f=files.getJSONObject(i);val name=f.getString("path");safe(incoming,name);require(name!="manifest.json" && !declared.containsKey(name));require(hashPattern.matches(f.getString("sha256")) && f.getLong("size")>=0);declared[name]=f}
                val links=m.optJSONArray("symlinks") ?: JSONArray();val linkNames=mutableSetOf<String>()
                for(i in 0 until links.length()){val l=links.getJSONObject(i);val n=l.getString("path");safe(incoming,n);require(!declared.containsKey(n) && linkNames.add(n));val target=l.getString("target");require(!target.startsWith('/') && !target.contains('\\') && !target.contains('\u0000'));val resolved=File(File(incoming,n).parentFile,target).canonicalFile;require(resolved.path.startsWith(incoming.canonicalPath+"/"))}
                require(linkNames.none { link -> (declared.keys+linkNames).any { it.startsWith("$link/") } })
                val seen=mutableSetOf<String>();var total=0L
                for(entry in archive.entries().asSequence()){
                    currentCoroutineContext().ensureActive();if(entry.name=="manifest.json")continue
                    if(entry.isDirectory){safe(incoming,entry.name.removeSuffix("/")).mkdirs();continue}
                    val f=declared[entry.name] ?: error("包含未声明文件：${entry.name}");require(seen.add(entry.name))
                    val size=f.getLong("size");require(size <= 8L*1024*1024*1024-total);total+=size;require(total<=8L*1024*1024*1024) { "包内容超出限制" }
                    val to=safe(incoming,entry.name);to.parentFile!!.mkdirs()
                    archive.getInputStream(entry).use { input -> FileOutputStream(to).use { output -> val buf=ByteArray(65536);var count=0L;while(true){val n=input.read(buf);if(n<0)break;count+=n;require(count<=size);output.write(buf,0,n)};require(count==size) } }
                    require(digest(to)==f.getString("sha256")) { "文件校验失败：${entry.name}" };Os.chmod(to.path,if(f.optBoolean("executable"))448 else 384)
                    if(seen.size%200==0)message.value="已验证 ${seen.size}/${declared.size} 个文件"
                }
                require(seen==declared.keys) { "包缺少声明文件" }
                for(n in listOf(m.getString("node"),m.getString("cli"),"node_modules/@deepseek-ai/dsh/package.json","bootstrap/guest-guard.mjs"))require(declared.containsKey(n))
                for(i in 0 until links.length()){val l=links.getJSONObject(i);val to=File(incoming,l.getString("path"));to.parentFile!!.mkdirs();Os.symlink(l.getString("target"),to.path)}
                for(n in linkNames)require(File(incoming,n).canonicalPath.startsWith(incoming.canonicalPath+"/") && File(incoming,n).exists())
                require(JSONObject(safe(incoming,"node_modules/@deepseek-ai/dsh/package.json").readText()).getString("version")==m.getString("dshVersion"))
                Files.move(incoming.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE);staging=null
                message.value="已导入 $id；请明确选择该版本，导入不会自动切换"
            }
        } finally { zip.delete();staging?.deleteRecursively() }
    }
    fun activate(c: Context, id: String) = operation {
        require(!ownedNativeAlive(c) && child?.isAlive!=true) { "先停止预览服务" }
        locked(c){val s=selection(c);val m=manifest(safe(slots(c),validId(id)));s.optJSONObject("active")?.let { old -> require(manifest(safe(slots(c),old.getString("id"))).getString("dataSchema")==m.getString("dataSchema")) { "需要单独迁移数据" } }
            s.put("previous",s.optJSONObject("active") ?: JSONObject.NULL);s.put("active",JSONObject().put("id",id).put("manifestSha256",digest(File(safe(slots(c),id),"manifest.json"))));s.put("revision",s.getInt("revision")+1);writeJson(File(stateDir(c),"selection.json"),s)}
        message.value="已选择 $id；尚未启动，不能视为业务验证通过"
    }
    fun rollback(c: Context) = operation {
        stopOwned(c)
        locked(c){val s=selection(c);val old=s.optJSONObject("previous") ?: error("没有上一个受管版本；不会擅自切回旧SDK")
            val root=safe(slots(c),old.getString("id"));val m=manifest(root);require(digest(File(root,"manifest.json"))==old.getString("manifestSha256"))
            s.optJSONObject("active")?.let { require(manifest(safe(slots(c),it.getString("id"))).getString("dataSchema")==m.getString("dataSchema")) { "不能自动反向转换会话格式" } }
            val current=s.optJSONObject("active") ?: JSONObject.NULL;s.put("active",old);s.put("previous",current);s.put("revision",s.getInt("revision")+1);writeJson(File(stateDir(c),"selection.json"),s)}
        message.value="已恢复上一个受管版本的选择；数据未反向转换，请再启动"
    }
    private fun ticks(pid: Int): String? = runCatching { File("/proc/$pid/stat").readText().let { it.substring(it.lastIndexOf(')')+1).trim().split(Regex("\\s+"))[19] } }.getOrNull()
    private fun ownedRecord(c: Context): JSONObject? = runCatching {
        val record=JSONObject(File(stateDir(c),"native-process.json").readText());val pid=record.getInt("pid");require(pid>1 && pid!=android.os.Process.myPid());require(ticks(pid)==record.getString("startTicks"))
        val expected=File(stateDir(c),"bootstrap/lib/native-launch.cjs").canonicalPath;require(record.getString("launcher")==expected)
        val argv=File("/proc/$pid/cmdline").readText().split('\u0000');require(argv.contains(expected));record
    }.getOrNull()
    private fun ownedNativeAlive(c: Context)=ownedRecord(c)!=null
    private suspend fun stopOwned(c: Context){
        val proc=child
        if(proc?.isAlive==true)proc.destroy() else ownedRecord(c)?.getInt("pid")?.let { Os.kill(it,OsConstants.SIGTERM) }
        val deadline=System.currentTimeMillis()+16000
        while(System.currentTimeMillis()<deadline && (proc?.isAlive==true || ownedNativeAlive(c)))delay(200)
        require(proc?.isAlive!=true && !ownedNativeAlive(c)) { "服务尚未正常退出，保留当前选择；不按端口杀未知进程" };child=null;harnessUrl=null
    }
    fun stop(c: Context){boot?.cancel();operation {stopOwned(c);message.value="预览服务已停止"}}
    fun start(c: Context, fullAccessAccepted: Boolean){
        boot=scope.launch {try {gate.withLock {
            require(fullAccessAccepted) { "请先明确接受当前proot环境没有可用沙箱、按完整权限运行" }
            require(SubsystemManager.state.value.phase !in listOf(SubsystemPhase.DOWNLOADING,SubsystemPhase.EXTRACTING)) { "基础环境操作尚未完成" }
            if(ownedNativeAlive(c)){message.value="已存在本应用拥有的预览进程";return@withLock}
            require(SubsystemManager.isInstalled(c)) { "缺少 Debian 基础环境" }
            require(!portOpen()) { "预览端口被其它进程占用；不会结束未知进程" }
            val selected=selection(c).optJSONObject("active") ?: error("先导入并选择受管运行时")
            val root=safe(slots(c),selected.getString("id"));val m=manifest(root);require(digest(File(root,"manifest.json"))==selected.getString("manifestSha256"))
            val bootstrap=File(stateDir(c),"bootstrap/lib/native-launch.cjs");require(bootstrap.isFile) { "先准备原生引导库" }
            val profile=File(data(c),"profiles/web").apply {mkdirs()}
            if(!File(profile,"package.json").exists()){
                File(profile,"package.json").writeText("""{"name":"managed-profile-web","private":true,"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}""")
                File(profile,"cordis.patch.yml").writeText("- id: workspace-controller\n  config:\n    documentsDirectory: /root/dshm-user/Documents\n- id: permission\n  config:\n    defaultPreset: danger-full-access\n- id: sandbox-policy\n  config:\n    mode: danger-full-access\n")
            }
            File(user(c),"Documents").mkdirs();val workspace=File(c.filesDir,"managed-workspace").apply {mkdirs()};val pidRoot=File(stateDir(c),"pids").apply {mkdirs()}
            val config=JSONObject().put("schemaVersion",1).put("slotRoot",root.path).put("manifestSha256",selected.getString("manifestSha256")).put("rootfs",SubsystemManager.rootfsDir(c).path).put("dataRoot",data(c).path).put("userHome",user(c).path).put("tmpRoot",temp(c).path).put("workspace",workspace.path).put("pidRoot",pidRoot.path).put("proot",SubsystemManager.prootBin(c).path).put("nativeLibDir",TermuxEnv.nativeLibDir(c).path).put("supportLibDir",File(TermuxEnv.prefix(c),"lib").path).put("resolvConf",SubsystemManager.resolvConf(c).path).put("sharedStorage",Environment.isExternalStorageManager()).put("port",PORT)
            val launch=File(stateDir(c),"launch.json");writeJson(launch,config);harnessUrl=null;val actual=AtomicBoolean(false)
            val pb=ProcessBuilder(TermuxEnv.nodeBin(c).path,bootstrap.path,launch.path).directory(workspace).redirectErrorStream(true)
            pb.environment().putAll(TermuxEnv.serverEnv(c));val proc=pb.start();child=proc
            scope.launch {proc.inputStream.bufferedReader().useLines {lines->lines.forEach {line->
                note(c,line)
                if(line.startsWith("[DSHM_MANAGED_ACTUAL] "))runCatching {val a=JSONObject(line.substringAfter("] "));actual.set(a.getString("slotId")==m.getString("id")&&a.getString("platform")=="linux"&&a.getString("dsh")==m.getString("dshVersion"))}
                Regex("http://[^\\s]+\\?token=[A-Za-z0-9_=-]+").find(line)?.value?.let {raw->runCatching {val u=Uri.parse(raw);if(u.host in listOf("127.0.0.1","localhost")&&u.port==PORT)harnessUrl=raw}}
            }};val code=proc.waitFor();if(child===proc){child=null;message.value="预览进程已退出（$code），请查看原生日志；不会无限自动重启"}}
            val deadline=System.currentTimeMillis()+120000;message.value="正在启动 ${m.getString("id")}…"
            while(System.currentTimeMillis()<deadline && proc.isAlive){currentCoroutineContext().ensureActive();if(actual.get() && transportReady()){message.value="Linux ${m.getString("nodeVersion")} / DSH ${m.getString("dshVersion")} 已响应；插件与账号业务仍需验证";return@withLock};delay(500)}
            error("预览未达到启动条件；保留日志，可从原生界面停止或恢复上版")
        }}catch(e: CancellationException){throw e}catch(e: Exception){message.value="启动未完成：${e.message}"}}
    }
    private fun portOpen(): Boolean = runCatching {Socket().use {it.connect(InetSocketAddress("127.0.0.1",PORT),800);true}}.getOrDefault(false)
    private fun transportReady(): Boolean = runCatching {val cn=URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection;try{cn.connectTimeout=800;cn.readTimeout=800;cn.responseCode in listOf(200,401)}finally{cn.disconnect()}}.getOrDefault(false)
    fun openUrl(): String?=harnessUrl
}
