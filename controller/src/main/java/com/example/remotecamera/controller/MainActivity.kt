package com.example.remotecamera.controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.remotecamera.controller.databinding.ActivityMainBinding
import java.net.Socket
import android.view.KeyEvent

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var socket: Socket? = null
    private var isConnected = false
    private var lastReceivedBitmap: Bitmap? = null

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredDevices = mutableMapOf<String, NsdServiceInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 连接按钮监听器
        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                val ip = binding.etIp.text.toString().trim()
                val portStr = binding.etPort.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入被控端 IP 地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val port = if (portStr.isEmpty()) 8888 else portStr.toInt()
                connectToReceiver(ip, port)
            }
        }

        // 快门拍摄按钮监听器
        binding.btnShutter.setOnClickListener {
            if (!isConnected || socket == null) {
                Toast.makeText(this, "请先连接被控端设备！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 快门缩放动画
            binding.btnShutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                binding.btnShutter.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }.start()

            Thread {
                try {
                    val outputStream = socket?.getOutputStream()
                    outputStream?.write("TAKE_PHOTO\n".toByteArray(Charsets.UTF_8))
                    outputStream?.flush()
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "触发拍摄失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        disconnect()
                    }
                }
            }.start()
        }

        // 保存照片按钮监听器
        binding.btnSave.setOnClickListener {
            val bitmap = lastReceivedBitmap
            if (bitmap != null) {
                savePhotoToLocalGallery(bitmap)
            } else {
                Toast.makeText(this, "没有照片可保存", Toast.LENGTH_SHORT).show()
            }
        }

        // 启动扫描局域网设备
        startDiscovery()
    }

    private fun connectToReceiver(ip: String, port: Int) {
        if (isConnected) {
            disconnect()
        }

        binding.tvConnectionStatus.text = "正在连接..."
        binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red)

        Thread {
            try {
                val clientSocket = Socket(ip, port)
                socket = clientSocket
                isConnected = true

                runOnUiThread {
                    binding.tvConnectionStatus.text = "已连接到: $ip:$port"
                    binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
                    binding.btnConnect.text = "断开连接"
                }

                val inputStream = clientSocket.getInputStream()
                val headerBytes = ByteArray(5)

                while (isConnected && !clientSocket.isClosed) {
                    // 读取数据头
                    var headerRead = 0
                    while (headerRead < 5) {
                        val r = inputStream.read(headerBytes, headerRead, 5 - headerRead)
                        if (r < 0) throw java.io.EOFException("被控端已关闭连接")
                        headerRead += r
                    }

                    val header = String(headerBytes, Charsets.US_ASCII)
                    if (header == "PHOTO") {
                        val length = readInt(inputStream)
                        val imageBuffer = ByteArray(length)
                        readFully(inputStream, imageBuffer)

                        val bitmap = BitmapFactory.decodeByteArray(imageBuffer, 0, length)
                        if (bitmap != null) {
                            runOnUiThread {
                                lastReceivedBitmap = bitmap
                                binding.ivPhotoPreview.setImageBitmap(bitmap)
                                binding.tvViewportPlaceholder.visibility = android.view.View.GONE
                                binding.btnSave.visibility = android.view.View.VISIBLE

                                // 自动保存到相册
                                savePhotoToLocalGallery(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "连接断开: ${e.message}", Toast.LENGTH_SHORT).show()
                    disconnect()
                }
            }
        }.start()
    }

    private fun readInt(inputStream: java.io.InputStream): Int {
        val b1 = inputStream.read()
        val b2 = inputStream.read()
        val b3 = inputStream.read()
        val b4 = inputStream.read()
        if ((b1 or b2 or b3 or b4) < 0) {
            throw java.io.EOFException()
        }
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun readFully(inputStream: java.io.InputStream, buffer: ByteArray) {
        var bytesRead = 0
        val size = buffer.size
        while (bytesRead < size) {
            val count = inputStream.read(buffer, bytesRead, size - bytesRead)
            if (count < 0) {
                throw java.io.EOFException("在读取图像数据时意外达到流的末尾 (已读取 $bytesRead / $size 字节)")
            }
            bytesRead += count
        }
    }

    private fun savePhotoToLocalGallery(bitmap: Bitmap) {
        val filename = "RemoteCapture_${System.currentTimeMillis()}.jpg"
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                Toast.makeText(this, "照片已成功保存到相册！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存相册失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDiscovery() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                runOnUiThread {
                    binding.tvDiscoveredTitle.text = "局域网发现设备 (正在扫描...)"
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == "_remotecamera._tcp.") {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            runOnUiThread {
                                val ip = resolvedServiceInfo.host.hostAddress ?: ""
                                val port = resolvedServiceInfo.port
                                val key = "$ip:$port"
                                if (!discoveredDevices.containsKey(key)) {
                                    discoveredDevices[key] = resolvedServiceInfo
                                    addDeviceButton(resolvedServiceInfo)
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(regType: String) {
                runOnUiThread {
                    binding.tvDiscoveredTitle.text = "局域网发现设备"
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager?.discoverServices("_remotecamera._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            // 忽略扫描启动异常
        }
    }

    private fun addDeviceButton(serviceInfo: NsdServiceInfo) {
        val ip = serviceInfo.host.hostAddress ?: return
        val port = serviceInfo.port

        val button = Button(this).apply {
            text = "${serviceInfo.serviceName}\n($ip)"
            textSize = 11f
            setPadding(12, 6, 12, 6)
            transformationMethod = null // 禁用自动大写

            // 赛博朋克霓虹风格按钮
            setTextColor(ContextCompat.getColor(context, R.color.primary_neon))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_dark)

            setOnClickListener {
                binding.etIp.setText(ip)
                binding.etPort.setText(port.toString())
                connectToReceiver(ip, port)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 16, 0)
        }
        button.layoutParams = params

        binding.containerDiscoveredDevices.addView(button)
    }

    private fun disconnect() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        runOnUiThread {
            binding.tvConnectionStatus.text = "未连接"
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            binding.btnConnect.text = "连接"
            binding.btnSave.visibility = android.view.View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isConnected && socket != null) {
                // 模拟快门按钮点击，从而保持动画与触发逻辑一致
                binding.btnShutter.performClick()
                return true
            } else {
                Toast.makeText(this, "请先连接被控端设备！", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
