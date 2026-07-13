package com.example.remotecamera.controlled

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import com.example.remotecamera.controlled.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = true

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val keepScreenOnHandler = Handler(Looper.getMainLooper())
    private val disableKeepScreenOnRunnable = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("[系统] 防黑屏功能已超时（5分钟），恢复默认屏幕休眠。")
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 请求权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // 启用 5 分钟防黑屏功能
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("[系统] 5分钟防黑屏已启用。")
        keepScreenOnHandler.postDelayed(disableKeepScreenOnRunnable, 5 * 60 * 1000L)

        // 启动 TCP 服务端
        startTcpServer()

        // 获取并展示本地 IP 地址
        val localIp = getLocalIpAddress()
        binding.tvIpAddress.text = "IP 地址: $localIp"

        // 注册局域网 NSD 服务
        registerService()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                log("未授予相机权限，应用无法正常运行。")
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                log("相机预览启动成功。")
            } catch (e: Exception) {
                log("启动相机预览失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startTcpServer() {
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                log("TCP 服务正在端口 8888 运行。等待控制端连接...")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("服务端 Socket 异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        clientSocket = socket
        val clientIp = socket.inetAddress.hostAddress
        log("控制端已连接，IP: $clientIp")
        runOnUiThread {
            binding.tvConnectionStatus.text = "已连接到控制端: $clientIp"
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            // 控制端连入时，取消超时，保持常亮
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnHandler.removeCallbacks(disableKeepScreenOnRunnable)
            log("[系统] 控制端连入，临时取消黑屏超时限制。")
        }

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (isRunning && !socket.isClosed) {
                val command = reader.readLine() ?: break
                log("收到拍照指令: $command")
                if (command.trim() == "TAKE_PHOTO") {
                    takePhotoAndSend(socket)
                }
            }
        } catch (e: Exception) {
            log("与控制端通信异常: ${e.message}")
        finally {
            try {
                socket.close()
            } catch (e: Exception) {}
            log("控制端已断开连接。")
            runOnUiThread {
                binding.tvConnectionStatus.text = "正在监听端口 8888..."
                binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
                // 断开后重新开始 5 分钟倒计时防黑屏
                keepScreenOnHandler.removeCallbacks(disableKeepScreenOnRunnable)
                keepScreenOnHandler.postDelayed(disableKeepScreenOnRunnable, 5 * 60 * 1000L)
                log("[系统] 控制端断开，启动 5 分钟防黑屏倒计时。")
            }
        }
    }

    private fun takePhotoAndSend(socket: Socket) {
        val imgCapture = imageCapture ?: run {
            log("相机未准备就绪。")
            return
        }

        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imgCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    log("拍照成功，保存至临时文件: ${photoFile.name}")
                    Thread {
                        try {
                            if (socket.isClosed || !socket.isConnected) {
                                log("Socket 已关闭，无法发送照片。")
                                return@Thread
                            }
                            val bytes = photoFile.readBytes()
                            val outputStream = socket.getOutputStream()

                            // 发送帧数据: "PHOTO" (5字节) + 长度 (4字节) + 原始数据
                            outputStream.write("PHOTO".toByteArray(Charsets.US_ASCII))
                            val length = bytes.size
                            outputStream.write(byteArrayOf(
                                (length shr 24).toByte(),
                                (length shr 16).toByte(),
                                (length shr 8).toByte(),
                                length.toByte()
                            ))
                            outputStream.write(bytes)
                            outputStream.flush()
                            log("成功将照片发送给控制端，大小: $length 字节。")

                            // 保存一份副本到本地公共相册
                            saveToGallery(photoFile)
                        } catch (e: Exception) {
                            log("网络发送图像失败: ${e.message}")
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    log("拍照失败: ${exception.message}")
                }
            }
        )
    }

    private fun saveToGallery(file: File) {
        val filename = "RemoteCamera_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RemoteCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                log("照片副本已保存至相册: Pictures/RemoteCamera/$filename")
                file.delete()
            } catch (e: Exception) {
                log("保存相册失败: ${e.message}")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                val addresses = networkInterface.inetAddresses
                for (inetAddress in Collections.list(addresses)) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            log("获取本地 IP 失败: ${ex.message}")
        }
        return "127.0.0.1"
    }

    private fun registerService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                log("NSD 服务成功注册: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                log("NSD 服务注册失败，代码: $arg1")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                log("NSD 服务成功注销。")
            }

            override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                log("NSD 服务注销失败，代码: $arg1")
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "RemoteCameraReceiver"
            serviceType = "_remotecamera._tcp"
            port = 8888
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("NSD 注册异常: ${e.message}")
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            val timeStamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            binding.tvLogs.append("[$timeStamp] $message\n")
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        keepScreenOnHandler.removeCallbacks(disableKeepScreenOnRunnable)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {}
    }
}
