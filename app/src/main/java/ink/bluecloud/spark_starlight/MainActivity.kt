package ink.bluecloud.spark_starlight

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import ink.bluecloud.spark_starlight.ui.theme.SparkstarlightTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.ExperimentalUuidApi

class MainActivity : ComponentActivity() {
    @ExperimentalPermissionsApi
    @ExperimentalUuidApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SparkstarlightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Main content with padding
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        GuideSystemScreen()
                    }
                }
            }
        }
    }
}

object ApiClient {
    private val client = OkHttpClient()
        .newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    interface UploadCallbacks {
        fun onSuccess(responseBody: ByteArray)
        fun onFailure(e: IOException)
        fun onError(responseCode: Int, errorMessage: String?)
    }

    fun uploadImage(
        imageData: ByteArray,
        imageMediaType: String,
        callbacks: UploadCallbacks
    ) {
        // 1. Construct the URL
        val url = "http://10.1.1.2:7447/uploadImage"
        Log.d("ApiClient", "Request URL: $url")
        Log.d("ApiClient", "Image data size: ${imageData.size} bytes")
        Log.d("ApiClient", "Image media type: $imageMediaType")

        // 2. Create the Request Body
        val mediaType = imageMediaType.toMediaTypeOrNull()
        if (mediaType == null) {
            callbacks.onFailure(IOException("Invalid media type: $imageMediaType"))
            return
        }
        val requestBody: RequestBody = imageData.toRequestBody(mediaType)

        // 3. Build the Request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // 4. Execute the Request Asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiClient", "Upload failed", e)
                callbacks.onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val responseCode = response.code
                        val errorBody = it.body?.string()
                        Log.e("ApiClient", "Upload request failed with code: $responseCode, body: $errorBody")
                        callbacks.onError(responseCode, errorBody)
                    } else {
                        val responseBody = it.body?.bytes()
                        if (responseBody != null) {
                            Log.d("ApiClient", "Upload successful: ${response.code}")
                            callbacks.onSuccess(responseBody)
                        } else {
                            Log.e("ApiClient", "Upload successful but response body was null")
                            callbacks.onFailure(IOException("Received null response body"))
                        }
                    }
                }
            }
        })
    }
}

@Composable
@ExperimentalPermissionsApi
@OptIn(ExperimentalPermissionsApi::class, ExperimentalUuidApi::class)
fun GuideSystemScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Switch state
    var isSwitchedOn by remember { mutableStateOf(false) }

    // Camera Permission State
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Create AudioPlayer instance
    val audioPlayer = remember { AudioPlayer(context) }

    // Cleanup AudioPlayer on disposal
    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.releaseMediaPlayer()
            Log.d("GuideSystemAudio", "AudioPlayer released")
        }
    }

    // Camera capture setup and execution logic
    CameraCaptureEffect(
        isSwitchedOn = isSwitchedOn && cameraPermissionState.status.isGranted,
        context = context,
        lifecycleOwner = lifecycleOwner,
        onImageCaptured = { pngBytes ->
            // Launch sending in a separate coroutine to avoid blocking capture
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ApiClient.uploadImage(pngBytes, "image/png", object : ApiClient.UploadCallbacks {
                        override fun onSuccess(responseBody: ByteArray) {
                            Log.d("GuideSystem", "Image upload successful: ${responseBody.size}")
                            CoroutineScope(Dispatchers.Main).launch {
                                audioPlayer.playAudio(responseBody)
                            }
                        }

                        override fun onFailure(e: IOException) {
                            Log.e("GuideSystem", "Image upload failed: ${e.message}", e)
                        }

                        override fun onError(responseCode: Int, errorMessage: String?) {
                            Log.e("GuideSystem", "Image upload error (code $responseCode): $errorMessage")
                        }
                    })
                } catch (e: Exception) {
                    Log.e("GuideSystem", "Error sending image to server: ${e.message}", e)
                }
            }
        }
    )

    // UI Layout
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Switch(
            checked = isSwitchedOn,
            onCheckedChange = { newValue ->
                isSwitchedOn = newValue
                if (newValue && !cameraPermissionState.status.isGranted) {
                    // Request permission if switching on and not granted
                    cameraPermissionState.launchPermissionRequest()
                }
                if (!newValue) {
                    Log.d("GuideSystem", "Switch turned OFF")
                    // CameraCaptureEffect will handle cancellation via LaunchedEffect key change
                } else if (cameraPermissionState.status.isGranted) {
                    Log.d("GuideSystem", "Switch turned ON and permission granted")
                } else {
                    Log.d("GuideSystem", "Switch turned ON but permission NOT granted")
                }
            },
            enabled = true // Enable switch regardless of permission to allow requesting it
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when {
                !cameraPermissionState.status.isGranted && isSwitchedOn -> "Camera permission needed"
                isSwitchedOn -> "System Active"
                else -> "System Idle"
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Button for testing audio playback ---
        Button(onClick = {
            Log.d("GuideSystemAudio", "Test Audio Button Clicked")
            val exampleData = run {
                val audioResId = R.raw.sample
                val inputStream = context.resources.openRawResource(audioResId)
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.toByteArray()
            }
            audioPlayer.playAudio(exampleData)
        }) {
            Text("Test Audio Playback")
        }
        // --- End Test Button ---
    }
}

// Effect to handle CameraX setup, capture loop, and cleanup
@Composable
fun CameraCaptureEffect(
    isSwitchedOn: Boolean,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onImageCaptured: (ByteArray) -> Unit
) {
    // Executor for CameraX callbacks
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // State for CameraProvider and ImageCapture use case
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // Setup CameraX
    LaunchedEffect(Unit) { // Runs once to get the CameraProvider
        try {
            cameraProvider = context.getCameraProvider()
            Log.d("GuideSystemCamera", "CameraProvider obtained")
        } catch (e: Exception) {
            Log.e("GuideSystemCamera", "Failed to get CameraProvider", e)
        }
    }

    // Start/Stop capture loop based on switch state and camera readiness
    LaunchedEffect(isSwitchedOn, cameraProvider) {
        if (isSwitchedOn && cameraProvider != null) {
            Log.d("GuideSystemCamera", "Setting up camera capture...")
            val currentCameraProvider = cameraProvider ?: return@LaunchedEffect

            try {
                // Unbind previous use cases before rebinding
                currentCameraProvider.unbindAll()

                // Build ImageCapture use case
                val capture = ImageCapture.Builder()
                    // .setTargetResolution(Size(640, 480)) // Optional: Set resolution
                    // .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Optional
                    .build()
                imageCapture = capture // Store the instance

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Bind use case to lifecycle
                currentCameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    capture
                )
                Log.d("GuideSystemCamera", "Camera bound to lifecycle with ImageCapture")
                // Start the capture loop
                while (coroutineContext.isActive && isSwitchedOn) {
                    Log.d("GuideSystemCamera", "Initiating image capture...")
                    try {
                        val pngBytes = captureImageAsPng(capture, cameraExecutor)
                        onImageCaptured(pngBytes)
                    } catch (e: Exception) {
                        Log.e("GuideSystemCamera", "Image capture or processing failed", e)
                        // Add delay before retrying if capture fails to avoid busy-looping on errors
                        delay(20000_00)
                    }
                    // Add a delay between captures if needed (e.g., 1 second)
                    delay(20000_00) // Adjust frequency as needed
                }
            } catch (exc: Exception) {
                Log.e("GuideSystemCamera", "Use case binding or capture loop failed", exc)
                imageCapture = null // Clear instance on failure
            } finally {
                Log.d("GuideSystemCamera", "Capture loop ending or interrupted. Unbinding camera.")
                // Ensure unbinding happens when the effect cancels or switch turns off
                // This might be redundant if lifecycle handles it, but explicit unbind is safer.
                // Consider moving unbindAll to a DisposableEffect onDispose
                // currentCameraProvider?.unbindAll() // Be careful with unbinding here vs DisposableEffect
            }
        } else {
            // If switched off or provider not ready, ensure capture stops
            Log.d("GuideSystemCamera", "Capture conditions not met (SwitchedOn: $isSwitchedOn, ProviderReady: ${cameraProvider != null}). Stopping capture.")
            // Coroutine cancellation handles stopping the loop.
            // We might want to explicitly unbind here too, or rely on DisposableEffect.
        }
    }

    // Cleanup camera resources
    DisposableEffect(lifecycleOwner) {
        onDispose {
            Log.d("GuideSystemCamera", "Disposing CameraCaptureEffect. Shutting down executor and unbinding.")
            cameraExecutor.shutdown()
            // Unbind all use cases managed by this effect when the composable disposes
            // This is crucial to release the camera properly.
            cameraProvider?.unbindAll()
        }
    }
}

suspend fun captureImageAsPng(
    imageCapture: ImageCapture,
    executor: Executor
): ByteArray {
    Log.d("GuideSystemCamera", "captureImageAsPng called")
    val imageProxy = imageCapture.takePicture(executor)
    Log.d("GuideSystemCamera", "ImageProxy received (Format: ${imageProxy.format})")

    try {
        val bitmap = imageProxy.toBitmap()

        // Compress the Bitmap to PNG format in memory
        val outputStream = ByteArrayOutputStream()
        // Use Dispatchers.IO for potentially blocking compression
        withContext(Dispatchers.IO) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }
        val pngBytes = outputStream.toByteArray()
        Log.d("GuideSystemCamera", "Image converted to PNG: ${pngBytes.size} bytes")
        return pngBytes
    } finally {
        imageProxy.close()
        Log.d("GuideSystemCamera", "ImageProxy closed")
    }
}

// Extension function to get CameraProvider using coroutines
suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
}

// Extension function to capture image using coroutines
suspend fun ImageCapture.takePicture(executor: Executor): ImageProxy = suspendCoroutine { continuation ->
    this.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            Log.d("GuideSystemCamera", "takePicture success")
            continuation.resume(image)
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("GuideSystemCamera", "takePicture error", exception)
            continuation.resumeWithException(exception)
        }
    })
}

class AudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun playAudio(audioBytes: ByteArray) {
        try {
            Log.d("AudioPlayer", "Playing audio (${audioBytes.size} bytes)")

            // Release previous instance if exists
            releaseMediaPlayer()

            // Create a temporary file to store the audio data
            val tempFile = File.createTempFile("audio", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            // Create and configure the MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.path)
                setOnCompletionListener {
                    Log.d("AudioPlayer", "Playback completed")
                    releaseMediaPlayer()
                    tempFile.delete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioPlayer", "MediaPlayer error: what=$what, extra=$extra")
                    releaseMediaPlayer()
                    tempFile.delete()
                    true
                }
                prepare()
                start()
            }

            Log.d("AudioPlayer", "Audio playback started")
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error playing audio: ${e.message}", e)
        }
    }

    fun releaseMediaPlayer() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
            mediaPlayer = null
            Log.d("AudioPlayer", "MediaPlayer released")
        }
    }
}