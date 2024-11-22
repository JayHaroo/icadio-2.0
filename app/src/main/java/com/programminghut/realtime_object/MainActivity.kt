package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.lang.Math.abs
import java.util.Locale


class MainActivity : AppCompatActivity() {

    //gesture
    private lateinit var gestureDetector: GestureDetector
    private lateinit var textView: TextView
    private lateinit var auto: TextView

    // cooldown
    private val cooldownPeriod = 5000L // 5 seconds in milliseconds
    private var lastSpeakTime = 0L

    private lateinit var labels: List<String>
    private val colors = listOf(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )
    private val paint = Paint()
    private lateinit var imageProcessor: ImageProcessor
    private var bitmap: Bitmap? = null
    private lateinit var imageView: ImageView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var model: SsdMobilenetV11Metadata1
    private lateinit var tts: TextToSpeech
    private var detectedObjectName = ""
    private var isFlashOn = false
    private var isDoubleTapped = false
    private lateinit var repeatHandler: Handler
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomLevel = 1.0f
    private var maxZoomLevel = 1.0f // This will be set based on camera capabilities
    private var isSpeaking = false // Flag to track if TTS is speaking

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechRecognizerIntent: Intent



    @Suppress("DEPRECATION")
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        getPermission()

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
        repeatHandler = Handler()

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())

        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                Log.d("MainActivity", "Surface texture available, opening camera")
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = false

            @SuppressLint("NewApi")
            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                processImage()
            }
        }

        if (isPhoneInWrongOrientation()) {
            Toast.makeText(this, "Please use the app in Portrait mode.", Toast.LENGTH_SHORT).show()
        }

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                Log.d("MainActivity", "TTS initialized successfully")

                // Set up the utterance progress listener
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true // Speech started
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false // Speech finished
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false // Handle speech error
                    }
                })
            } else {
                Log.e("MainActivity", "TTS initialization failed")
            }

            fun isSimilar(input: String, target: String): Boolean {
                // Calculate similarity using Levenshtein Distance or other algorithms
                return input.lowercase().contains(target.lowercase())
            }

            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                keepListening()
                speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                speechRecognizer?.run {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {}
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (matches != null) {
                                for (result in matches) {
                                    Log.d("SpeechRecognition", "Recognized: $result")
                                    if (isSimilar(result, "scan") || isSimilar(result, "detect")) {
                                        val action = if (result.equals("scan", ignoreCase = true)) "Scan" else "Detect"
                                        textView.text = "Detected command: $action. Proceeding..."
                                        textView.text = "CAPTION: \n " + speakDetectedObject()
                                        break
                                    }
                                }
                            }
                            startListening() // Restart listening after each result
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
                startListening() // Start listening when the app opens // Start listening when the app opens
        }

        /*
        *                               GESTURE FEATURE
        * */
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        textView = findViewById(R.id.textView)
        gestureDetector = GestureDetector(this, GestureListener())

        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

    }

    private fun startListening() {
        Handler(Looper.getMainLooper()).post {
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    private fun keepListening() {
        val handler = Handler(Looper.getMainLooper())
        val restartInterval = 3000L // Restart every 10 seconds

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (speechRecognizer != null) {
                    speechRecognizer?.cancel()
                    startListening()
                }
                handler.postDelayed(this, restartInterval)
            }
        }, restartInterval)
    }



    private fun isPhoneInWrongOrientation(): Boolean {
        val currentOrientation = resources.configuration.orientation

        return when (currentOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                Log.e("OrientationCheck", "Phone is in Landscape orientation.")
                tts.speak("Phone is in wrong orientation. Please rotate to portrait mode.", TextToSpeech.QUEUE_FLUSH, null, null)
                true
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                Log.d("OrientationCheck", "Phone is in Portrait orientation.")
                false
            }
            else -> {
                Log.e("OrientationCheck", "Unknown orientation.")
                tts.speak("Phone is in an unsupported orientation. Please rotate to portrait mode.", TextToSpeech.QUEUE_FLUSH, null, null)
                true
            }
        }
    }


    fun vibrate(){
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE) )
        }else{
            @Suppress("DEPRECATION")
            vib.vibrate(200)
        }
    }

    /*
    *                               GESTURE FEATURE
    * */
    @Suppress("DEPRECATION")
    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true // Required for GestureDetector to recognize other gestures
        }

        @SuppressLint("SetTextI18n")
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            vibrate() // Vibrate on single tap
            val caption = speakDetectedObject() // Get the caption
            textView.text = "CAPTION:\n$caption" // Update UI
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            openWebsite() // Trigger the website opening
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // Check if the swipe is predominantly horizontal
            if (abs(velocityX) > abs(velocityY)) {
                toggleFlash()
                return true
            }
            return false // Ignore vertical flings
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            isDoubleTapped = !isDoubleTapped // Toggle between manual and automatic modes

            if(isDoubleTapped){
                tts.speak("Now in Automatic Mode", TextToSpeech.QUEUE_FLUSH, null, null)
            }else{
                tts.speak("Now in Manual Mode", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            // Update auto mode text
            val manual = findViewById<ImageView>(R.id.Manual) // Specify the type explicitly
            manual.setImageResource(if (isDoubleTapped) R.drawable.auto else R.drawable.manual)

            // Update direction instructions
            val directionText = findViewById<TextView>(R.id.directionText)
            directionText.text = if (isDoubleTapped) {
                """
                DIRECTION TO USE:
                TAP TO GENERATE CAPTION
                DOUBLE TAP TO MANUAL MODE
                SWIPE LEFT/RIGHT TO TOGGLE FLASH
                LONG PRESS TO GO ONLINE MODE
            """.trimIndent()
            } else {
                """
                DIRECTION TO USE:
                TAP TO GENERATE CAPTION
                DOUBLE TAP TO AUTOMATIC MODE
                SWIPE LEFT/RIGHT TO TOGGLE FLASH
                LONG PRESS TO GO ONLINE MODE
            """.trimIndent()
            }

            return true
        }
    }


    /*
    *                               GESTURE FEATURE
    * */

    @Suppress("DEPRECATION")
    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            try {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraManager.cameraIdList[0])
                val zoomRange = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                if (zoomRange != null) {
                    maxZoomLevel = zoomRange

                    // Calculate the new zoom level based on pinch gesture
                    currentZoomLevel *= detector.scaleFactor
                    currentZoomLevel = currentZoomLevel.coerceIn(1.0f, maxZoomLevel)

                    val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    val zoomRect = getZoomRect(currentZoomLevel)
                    captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)

                    val surfaceTexture = textureView.surfaceTexture
                    val surface = Surface(surfaceTexture)
                    captureRequestBuilder.addTarget(surface)

                    cameraDevice.run {
                        this.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("MainActivity", "Zoom configuration failed")
                            }
                        }, handler)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling zoom", e)
            }
            return true
        }

        // Calculate the zoom rect based on the zoom level
        private fun getZoomRect(zoomLevel: Float): Rect {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraManager.cameraIdList[0])
            val sensorRect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return Rect()

            val cropW = (sensorRect.width() / zoomLevel).toInt()
            val cropH = (sensorRect.height() / zoomLevel).toInt()
            val cropX = (sensorRect.width() - cropW) / 2
            val cropY = (sensorRect.height() - cropH) / 2

            return Rect(cropX, cropY, cropX + cropW, cropY + cropH)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("MainActivity", "Camera opened")
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d("MainActivity", "Camera disconnected")
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.d("MainActivity", "Camera error: $error")
                    camera.close()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening camera", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            val surface = Surface(surfaceTexture)

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            // Set the flash mode based on the isFlashOn flag
            if (isFlashOn) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            cameraDevice.run {
                this.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d("MainActivity", "Camera preview session configured")
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("MainActivity", "Camera preview session configuration failed")
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating camera preview session", e)
        }
    }


    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        if(isFlashOn) {
            tts.speak("Flash Opened", TextToSpeech.QUEUE_FLUSH, null, null)
        }else {
            tts.speak("Flash Closed", TextToSpeech.QUEUE_FLUSH, null, null)
        }
        createCameraPreviewSession()
    }

    private fun speakDetectedObject(): String {
        if (detectedObjectName.isNotEmpty()) {
            // Count detected objects for smarter sentence construction
            val objects = detectedObjectName.split(", ")
            val objectDescriptions = objects.map {
                val parts = it.split(" ")
                val count = parts[0].toInt()
                val name = parts.subList(1, parts.size).joinToString(" ")
                count to name
            }

            // Construct sentences based on detected objects
            val sentences = mutableListOf<String>()
            for ((count, name) in objectDescriptions) {
                val sentence = when {
                    count == 1 -> {
                        listOf(
                            "There's a single $name in front of you.",
                            "I can see one $name nearby.",
                            "A $name is directly ahead.",
                            "You have a $name right in front of you."
                        ).random()
                    }
                    count in 2..4 -> {
                        listOf(
                            "I see $count ${name}s nearby.",
                            "There are a few ${name}s in your vicinity.",
                            "You’re looking at $count ${name}s.",
                            "I’ve detected $count ${name}s ahead."
                        ).random()
                    }
                    count > 4 -> {
                        listOf(
                            "There are several ${name}s around you.",
                            "I can spot many ${name}s in your view.",
                            "You have a group of ${name}s nearby.",
                            "There seems to be a crowd of ${name}s in front of you."
                        ).random()
                    }
                    else -> {
                        "An object is detected." // Default fallback in case of unexpected input
                    }
                }
                sentences.add(sentence)
            }

            // Combine all sentences into one paragraph
            val finalSentence = sentences.joinToString(" ")

            // Speak the constructed sentence
            tts.speak(finalSentence, TextToSpeech.QUEUE_FLUSH, null, "object_detected")
            return finalSentence
        }

        // Default sentence if no object detected
        val noObjectSentences = listOf(
            "There’s currently nothing detected in front of you.",
            "I’m not seeing any recognizable objects at the moment.",
            "It looks clear, with no objects in your view.",
            "Nothing appears to be directly ahead of you."
        )
        val noObjectSentence = noObjectSentences.random()
        tts.speak(noObjectSentence, TextToSpeech.QUEUE_FLUSH, null, "nothing_detected")
        return noObjectSentence
    }


    private fun openWebsite() {
        val intent = Intent(this, OnlineMode::class.java)
        startActivity(intent)
        tts.speak("Now in Online Mode", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
        tts.shutdown()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        this.startActivity(intent)
        this.finishAffinity()
    }

    @SuppressLint("NewApi")
    private fun getPermission() {
        // List of permissions to request
        val permissionsToRequest = mutableListOf<String>()

        // Check for CAMERA permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }

        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }

        // Request permissions if any are missing
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> {
                // Check if both permissions are granted
                val cameraPermissionGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                val audioPermissionGranted = grantResults.isNotEmpty() && grantResults[1] == PackageManager.PERMISSION_GRANTED

                if (!cameraPermissionGranted || !audioPermissionGranted) {
                    // Handle the case where permissions are not granted (you could show a message)
                    Toast.makeText(this, "Permissions are required to proceed", Toast.LENGTH_SHORT).show()
                }else{
                    restartApp()
                }
            }
        }
    }

    @SuppressLint("NewApi", "SetTextI18n")
    private fun processImage() {
        try {
            bitmap = textureView.bitmap
            if (bitmap == null) {
                Log.w("MainActivity", "Bitmap is null")
                return
            }
            var image = TensorImage.fromBitmap(bitmap!!)
            image = imageProcessor.process(image)

            val outputs = model.process(image)
            val locations = outputs.locationsAsTensorBuffer.floatArray
            val classes = outputs.classesAsTensorBuffer.floatArray
            val scores = outputs.scoresAsTensorBuffer.floatArray

            val mutable = bitmap!!.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)

            val h = mutable.height
            val w = mutable.width
            paint.textSize = h / 15f
            paint.strokeWidth = h / 85f

            // Dictionary to keep track of detected object counts
            val detectedObjects = mutableMapOf<String, Int>()

            var hasDetectedObject = false // Flag to check if objects are detected

            scores.forEachIndexed { index, fl ->
                if (fl > 0.65) {
                    val x = index * 4
                    paint.color = colors[index]
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(
                        RectF(locations[x + 1] * w, locations[x] * h, locations[x + 3] * w, locations[x + 2] * h),
                        paint
                    )
                    paint.style = Paint.Style.FILL
                    val detectedObject = labels[classes[index].toInt()]
                    canvas.drawText(detectedObject, locations[x + 1] * w, locations[x] * h, paint)

                    // Add detected object to the dictionary
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        detectedObjects[detectedObject] = detectedObjects.getOrDefault(detectedObject, 0) + 1
                    }
                    hasDetectedObject = true // Set flag if object is detected
                }
            }

            imageView.setImageBitmap(mutable)
            detectedObjectName = detectedObjects.map { "${it.value} ${it.key}" }.joinToString(", ")

            // If in automatic mode (double-tap) and an object is detected, speak
            if (isDoubleTapped && hasDetectedObject && !isSpeaking) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSpeakTime >= cooldownPeriod) {
                    lastSpeakTime = currentTime
                    textView.text = "CAPTION: \n " + speakDetectedObject()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing image", e)
        }
    }
}
