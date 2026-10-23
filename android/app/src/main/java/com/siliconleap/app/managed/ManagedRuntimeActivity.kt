package com.siliconleap.app.managed

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Independent native recovery/control panel; never needs a working Harness page. */
class ManagedRuntimeActivity : ComponentActivity() {
    private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let {ManagedRuntimeManager.importBundle(this,it)}}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1)
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(28,28,28,28)}
        fun text(value: String)=TextView(this).also {it.text=value;it.setPadding(0,12,0,12);column.addView(it)}
        text("DSHM · Linux 0.1.7 独立预览").textSize=22f
        text("独立包名与39017端口，不覆盖现有App。当前账号和私有数据不会自动导入；同版本插件仍需保留本地适配。")
        val status=text("尚未启动")
        fun button(label: String, action: ()->Unit){column.addView(Button(this).apply{text=label;setOnClickListener{action()}})}
        button("1. 准备独立原生引导库"){ManagedRuntimeManager.prepare(this)}
        button("2. 安装 Debian 基础环境"){ManagedRuntimeManager.installRootfs(this)}
        button("3. 导入可信 Linux 运行时包（ZIP）"){importer.launch(arrayOf("application/zip","application/octet-stream"))}
        val slots=Spinner(this);column.addView(slots)
        var names=emptyList<String>()
        fun refresh(){val updated=ManagedRuntimeManager.slotNames(this);if(updated!=names){names=updated;slots.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,updated)}}
        button("4. 选择此版本（请先停止预览服务）"){
            val selected=slots.selectedItem?.toString()
            if(selected==null)Toast.makeText(this,"请先导入运行时包",Toast.LENGTH_SHORT).show() else ManagedRuntimeManager.activate(this,selected)
        }
        val full=CheckBox(this).apply {text="我接受完整权限运行：当前proot内没有可用沙箱；这不代表Android root或安全隔离"};column.addView(full)
        button("启动预览服务"){
            if(!full.isChecked)Toast.makeText(this,"请先阅读并确认访问范围",Toast.LENGTH_LONG).show()
            else ManagedRuntimeService.start(this)
        }
        button("正常停止预览服务"){ManagedRuntimeService.stop(this)}
        button("从当前入口打开 Harness"){
            val url=ManagedRuntimeManager.openUrl()
            if(url==null)Toast.makeText(this,"尚未收到本次启动入口；请查看原生日志",Toast.LENGTH_LONG).show()
            else startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
        }
        button("恢复上一个受管版本（不反向转换数据）"){ManagedRuntimeManager.rollback(this)}
        val logs=text("")
        button("查看本次原生日志"){logs.text=ManagedRuntimeManager.privateLog(this)}
        text("这是工程预览，不代表所有账号、生成或渲染业务已验收。不要卸载正式App或清除其数据。")
        setContentView(ScrollView(this).apply{addView(column)})
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){ManagedRuntimeManager.status.collect {status.text=it+"\n当前选择："+(ManagedRuntimeManager.activeName(this@ManagedRuntimeActivity)?:"无");refresh()}}}
    }
}
