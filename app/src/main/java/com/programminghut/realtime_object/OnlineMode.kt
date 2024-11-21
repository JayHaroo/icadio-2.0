package com.programminghut.realtime_object

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

class OnlineMode : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraPreview: TextureView
    private lateinit var captionText: TextView
    private var loading = false
    private lateinit var gestureDetector: GestureDetector

    // Camera variables
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var cameraHandler: Handler
    private lateinit var cameraThread: HandlerThread
    private val cameraId = "0" // Usually "0" for the back camera
    private var imageBuffer: ByteArrayOutputStream? = null

    // TextToSpeech variable
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsInitialized = false
    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online_mode)

        // Initialize views
        cameraPreview = findViewById(R.id.cameraPreview)
        captionText = findViewById(R.id.captionText)

        // Initialize GestureDetector
        gestureDetector = GestureDetector(this, GestureListener())

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Set a touch listener on the root view to detect taps and long presses
        val rootView = findViewById<View>(R.id.cameraPreview).rootView
        rootView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleGenerateCaption()
            }
            true
        }

        // Set up the TextureView listener
        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d("CameraState", "onSurfaceTextureAvailable: Starting camera")
                startCamera() // Start the camera preview once TextureView is ready
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Handle changes in TextureView size if needed
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // You can process the frame here if needed
            }
        }
    }

    private fun startCamera() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Check for camera permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("CameraError", e.message ?: "Error opening camera")
        }
    }

    private fun startPreview() {
        try {
            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val texture = cameraPreview.surfaceTexture
            texture?.setDefaultBufferSize(cameraPreview.width, cameraPreview.height)
            val surface = Surface(texture)

            captureRequestBuilder.addTarget(surface)

            // Set the flash mode based on `isFlashOn`
            if (isFlashOn) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraError", "Capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("CameraError", e.message ?: "Error starting camera preview")
        }
    }

    private fun handleGenerateCaption() {
        captionText.text = "GENERATING...."
        if (loading) return

        // Capture image from TextureView
        val imageBlob = captureImage()
        if (imageBlob == null) return

        loading = true

        val formData = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "webcam_image.jpg",
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageBlob))
            .build()

        val request = Request.Builder()
            .url("https://icadio-server.vercel.app/caption")
            .post(formData)
            .build()

        val client = OkHttpClient()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Log.e("Error", e.message ?: "An error occurred")
                    setCaption("An error occurred.")
                    setText("Error: ${e.message}")
                    loading = false
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    try {
                        val result = response.body?.string()
                        if (response.isSuccessful) {
                            val jsonObject = JSONObject(result)
                            val caption = jsonObject.getString("caption")
                            setCaption(caption)
                            setText(caption)

                            // Speak the caption using TTS
                            speakCaption(caption)

                            // Trigger vibration when caption is ready
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(200, 100, 200), -1))
                            } else {
                                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                                vibrator.vibrate(200)
                            }
                        } else {
                            val jsonObject = JSONObject(result)
                            val error = jsonObject.getString("error")
                            Log.e("Error", error)
                            setCaption("Failed to generate caption.")
                            setText("Error: $error")
                        }
                    } catch (e: Exception) {
                        Log.e("Error", e.message ?: "An error occurred")
                        setCaption("An error occurred.")
                        setText("Error: ${e.message}")
                    } finally {
                        loading = false
                    }
                }
            }
        })
    }

    private fun captureImage(): ByteArray? {
        return try {
            // Capture bitmap from TextureView
            val bitmap = cameraPreview.bitmap ?: return null

            // Convert the bitmap to a byte array (JPEG)
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
            byteArrayOutputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("CaptureError", e.message ?: "Error capturing image")
            null
        }
    }

    private fun setCaption(caption: String) {
        captionText.text = caption
    }

    private fun setText(text: String) {
        captionText.text = text
    }

    // Method to handle TextToSpeech initialization
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.US)
            isTtsInitialized = !(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    // Method to speak the caption using TextToSpeech
    private fun speakCaption(caption: String) {
        if (isTtsInitialized) {
            textToSpeech.speak(caption, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.e("TTS", "TextToSpeech not initialized")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 101) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startCamera()
            } else {
                // Handle permission denial
                Log.e("PermissionError", "Camera permission denied")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // GestureListener to handle long press
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            super.onLongPress(e)
            // Start MainActivity on long press
            val intent = Intent(this@OnlineMode, MainActivity::class.java)
            startActivity(intent)
            textToSpeech.speak("Now in Offline Mode", TextToSpeech.QUEUE_FLUSH, null, null)
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // Check if the swipe is predominantly horizontal
            if (Math.abs(velocityX) > Math.abs(velocityY)) {
                toggleFlash()
                return true
            }
            return false // Ignore vertical flings
        }
    }

    private fun toggleFlash() {
        isFlashOn = !isFlashOn

        // Stop current preview and start a new one with updated flash settings
        if (::captureSession.isInitialized) {
            try {
                captureSession.stopRepeating()
                startPreview() // Restart preview with the new flash state
            } catch (e: CameraAccessException) {
                Log.e("CameraError", "Failed to stop the repeating request: ${e.message}")
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        cameraDevice.close()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
}
