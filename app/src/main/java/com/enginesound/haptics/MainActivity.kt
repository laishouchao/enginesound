package com.enginesound.haptics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.enginesound.haptics.service.HapticEngineService
import com.enginesound.haptics.ui.HapticAppUI
import com.enginesound.haptics.ui.HapticViewModel
import com.enginesound.haptics.ui.theme.EngineSoundHapticsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HapticViewModel by viewModels()

    private var hapticService: HapticEngineService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HapticEngineService.LocalBinder
            val service = localBinder?.getService()
            if (service != null) {
                hapticService = service
                isServiceBound = true
                viewModel.bindService(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.unbindService()
            hapticService = null
            isServiceBound = false
        }
    }

    // 1. 本地音频文件选择器
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryFileName(uri) ?: "选定音频文件"
            viewModel.setSelectedFile(uri, fileName)
        }
    }

    // 2. 全局系统音频捕获权限授权启动器 (MediaProjection)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection != null) {
                hapticService?.setMediaProjection(projection)
                hapticService?.startOrResume()
            } else {
                Toast.makeText(this, "未能获取系统全局音频捕获句柄", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "用户取消了系统全局音频捕获授权", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. 动态运行时权限请求
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "⚠️ 全局音频捕获需要录音权限支持", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()
        startAndBindHapticService()

        setContent {
            EngineSoundHapticsTheme {
                HapticAppUI(
                    viewModel = viewModel,
                    onPickAudioFile = {
                        pickAudioLauncher.launch("audio/*")
                    },
                    onRequestMediaProjection = {
                        requestMediaProjectionPermission()
                    }
                )
            }
        }
    }

    private fun requestMediaProjectionPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindHapticService() {
        val intent = Intent(this, HapticEngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
