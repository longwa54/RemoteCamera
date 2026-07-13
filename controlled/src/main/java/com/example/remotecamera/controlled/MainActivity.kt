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

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Start TCP server
        startTcpServer()

        // Display IP Address
        val localIp = getLocalIpAddress()
        binding.tvIpAddress.text = "IP Address: $localIp"

        // Register NSD service
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
                log("Camera permissions not granted. Unable to proceed.")
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
                log("Camera preview started.")
            } catch (e: Exception) {
                log("Error starting camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startTcpServer() {
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                log("TCP Server running on port 8888. Waiting for connections...")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("Server Socket Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        clientSocket = socket
        val clientIp = socket.inetAddress.hostAddress
        log("Client connected: $clientIp")
        runOnUiThread {
            binding.tvConnectionStatus.text = "Connected to $clientIp"
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
        }

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (isRunning && !socket.isClosed) {
                val command = reader.readLine() ?: break
                log("Command received: $command")
                if (command.trim() == "TAKE_PHOTO") {
                    takePhotoAndSend(socket)
                }
            }
        } catch (e: Exception) {
            log("Client error: ${e.message}")
        } finally {
            socket.close()
            log("Client disconnected.")
            runOnUiThread {
                binding.tvConnectionStatus.text = "Listening on port 8888..."
                binding.statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            }
        }
    }

    private fun takePhotoAndSend(socket: Socket) {
        val imgCapture = imageCapture ?: run {
            log("Camera not initialized.")
            return
        }

        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imgCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    log("Image saved to temp cache: ${photoFile.name}")
                    Thread {
                        try {
                            if (socket.isClosed || !socket.isConnected) {
                                log("Socket closed, cannot send photo.")
                                return@Thread
                            }
                            val bytes = photoFile.readBytes()
                            val outputStream = socket.getOutputStream()

                            // Write headers: Command type "PHOTO", then 4 bytes length, then data
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
                            log("Successfully sent photo of size $length bytes.")

                            // Save copy to local gallery
                            saveToGallery(photoFile)
                        } catch (e: Exception) {
                            log("Error sending photo over network: ${e.message}")
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    log("Photo capture error: ${exception.message}")
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
                log("Saved copy to gallery: Pictures/RemoteCamera/$filename")
                file.delete()
            } catch (e: Exception) {
                log("Error saving photo to gallery: ${e.message}")
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
            log("Error resolving local IP: ${ex.message}")
        }
        return "127.0.0.1"
    }

    private fun registerService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                log("NSD Service Registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                log("NSD Service Registration Failed: $arg1")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                log("NSD Service Unregistered.")
            }

            override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                log("NSD Service Unregistration Failed: $arg1")
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
            log("NSD Register Error: ${e.message}")
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
