package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.Window
import android.view.Window.*
import android.view.WindowManager.LayoutParams.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.TextView
import android.widget.RelativeLayout
import androidx.annotation.RequiresApi

class MainActivity : AppCompatActivity() {

    //gesture
    private lateinit var gestureDetector: GestureDetector
    private lateinit var textView: TextView
    private lateinit var auto: TextView
    private lateinit var rootLayout: RelativeLayout

    // cooldown
    private val cooldownPeriod = 5000L // 3 seconds in milliseconds
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
    private var test = "asd"
    private var isDoubleTapped = false
    private lateinit var repeatHandler: Handler
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomLevel = 1.0f
    private var maxZoomLevel = 1.0f // This will be set based on camera capabilities
    private var isSpeaking = false // Flag to track if TTS is speaking



    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        getPermission()

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
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

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                processImage()
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

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

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false // Handle speech error
                    }
                })
            } else {
                Log.e("MainActivity", "TTS initialization failed")
            }
        }


        /*
        *                               GESTURE FEATURE
        * */
        rootLayout = findViewById(R.id.rootLayout)
        textView = findViewById(R.id.textView)
        auto = findViewById(R.id.Automatic)
        gestureDetector = GestureDetector(this, GestureListener())

        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        /*
        *                               GESTURE FEATURE
        * */

    }



    /*
    *                               GESTURE FEATURE
    * */
    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true // Required for GestureDetector to work
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            speakDetectedObject()
            textView.text = "Caption: " + speakDetectedObject()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            openWebsite()
        }

        override fun onFling(
            p0: MotionEvent?,
            e1: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            toggleFlash()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            isDoubleTapped = !isDoubleTapped // Toggle on/off
            if(isDoubleTapped)
                auto.text = "Automatic"
            else
                auto.text = "Manual"
            return true
        }

    }

    /*
    *                               GESTURE FEATURE
    * */

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

                    cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("MainActivity", "Zoom configuration failed")
                        }
                    }, handler)
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
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }


    private fun openWebsite() {
        val intent = Intent(this, OnlineMode::class.java)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
        tts.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
                override fun onOpened(p0: CameraDevice) {
                    Log.d("MainActivity", "Camera opened")
                    cameraDevice = p0
                    createCameraPreviewSession()
                }

                override fun onDisconnected(p0: CameraDevice) {
                    Log.e("MainActivity", "Camera disconnected")
                }

                override fun onError(p0: CameraDevice, p1: Int) {
                    Log.e("MainActivity", "Camera error: $p1")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening camera", e)
        }
    }

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

            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d("MainActivity", "Camera preview session configured")
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("MainActivity", "Camera preview session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating camera preview session", e)
        }
    }


    @RequiresApi(Build.VERSION_CODES.M)
    private fun getPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            getPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
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
                    detectedObjects[detectedObject] = detectedObjects.getOrDefault(detectedObject, 0) + 1
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
                    textView.text = "Caption: " + speakDetectedObject()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing image", e)
        }
    }

    private fun speakDetectedObject(): String {
        if (detectedObjectName.isNotEmpty()) {
            // List of sentence templates
            val sentences = listOf(
                "There is a $detectedObjectName in front of you.",
                "You are looking at $detectedObjectName.",
                "A $detectedObjectName is detected in front of you.",
                "I see $detectedObjectName in your view.",
                "Watch out! There are $detectedObjectName ahead.",
                "You have $detectedObjectName right in front of you.",
                "Notice the $detectedObjectName in your surroundings.",
                "There are $detectedObjectName directly in your path.",
                "$detectedObjectName is within your sight.",
                "Look ahead, there are $detectedObjectName."
            )
            // Select a random sentence template
            val randomSentence = sentences.random()

            // Speak the sentence with a unique utteranceId
            tts.speak(randomSentence, TextToSpeech.QUEUE_FLUSH, null, "object_detected_utterance")
            return randomSentence
        }

        val sentences = listOf(
            "There is nothing detected in front of you.",
        )
        // Select a random sentence template
        val randomSentence = sentences.random()

        // Speak the sentence with a unique utteranceId
        tts.speak(randomSentence, TextToSpeech.QUEUE_FLUSH, null, "nothing_detected_utterance")
        return randomSentence
    }

    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        createCameraPreviewSession()
    }
}