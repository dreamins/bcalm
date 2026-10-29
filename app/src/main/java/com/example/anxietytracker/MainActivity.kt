package com.example.anxietytracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    /** Check-in opened from its notification; shown as the one-tap sheet until answered or dismissed. */
    private var promptId by mutableStateOf<String?>(null)

    private fun takePrompt(i: Intent?) {
        i?.getStringExtra(Prompts.EXTRA_ID)?.let { promptId = it; i.removeExtra(Prompts.EXTRA_ID) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takePrompt(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Prompts.migrate(this)
        if (savedInstanceState == null) takePrompt(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            BcalmTheme(dark) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val vm: MainViewModel = viewModel()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(); Prompts.plan(ctx); CsvSync.enqueueIfEnabled(ctx) }   // also picks up outside edits of the sync file
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAppStopped() }
                AppScreen(vm, onExport = { exportCsv() }, initialTab = intent.getIntExtra("tab", 0),
                    promptId = promptId, onPromptDone = { promptId = null })
            }
        }
    }

    private fun exportCsv() {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "anxiety_log.csv")
        f.writeText(Csv.build(Store.entries.value, Store.csvSettings()))
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Export CSV"))
    }
}
