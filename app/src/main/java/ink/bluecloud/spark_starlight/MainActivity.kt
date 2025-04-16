package ink.bluecloud.spark_starlight

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import ink.bluecloud.spark_starlight.ui.theme.SparkstarlightTheme
import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

@ExperimentalUuidApi
object UUID {
    val uuid by lazy {
        Uuid.random()
    }
}

@ExperimentalUuidApi
fun sendToServer(image: ByteArray, zenoh: Session, context: Context) {
    Log.d("GuideSystem", "Sending image to server, from: ${UUID.uuid}")
    Log.d("GuideSystem", "Image size: ${image.size} bytes")
    val image = run {
        val audioResId = R.raw.sample_image
        val inputStream = context.resources.openRawResource(audioResId)
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
        outputStream.toByteArray()
    }
    zenoh.put(
        KeyExpr.tryFrom("spark/server").getOrNull()?: run {
            Log.e("GuideSystem", "Failed to create KeyExpr")
            return
        },
        ZBytes.from("uploadImage:${UUID.uuid}"),
        attachment = ZBytes.from(image)
    ).onFailure {
        Log.e("GuideSystem", "Failed to send image to server: ${it.message}")
    }.onSuccess {
        Log.d("GuideSystem", "Image sent successfully")
    }
}

@Composable
@OptIn(UnstableApi::class)
@ExperimentalPermissionsApi
@ExperimentalUuidApi
fun GuideSystemScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val config = Config.fromJson(
    """
    {
      mode: "client",
      connect: {
        endpoints: ["tcp/10.1.1.2:7447"],
      },
    }
    """.trimIndent()
    ).getOrThrow()
    val zenoh = Zenoh.open(config)
        .onFailure {
            Toast.makeText(context, "Failed to open Zenoh session", Toast.LENGTH_SHORT).show()
            Log.e("GuideSystem", "Failed to open Zenoh session: ${it.message}")
        }
        .getOrNull() ?: run {
            return
        }

    // Switch state
    var isSwitchedOn by remember { mutableStateOf(false) }

    // Camera Permission State
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    // ExoPlayer instance using remember for lifecycle management
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            // Optional: Add listeners for playback state, errors etc.
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        Log.d("GuideSystemAudio", "Playback ended")
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("GuideSystemAudio", "Player Error: ${error.message}", error)
                }
            })
        }
    }

    zenoh.declareSubscriber(KeyExpr.tryFrom("spark/client/*").getOrNull() ?: return, { sample ->
        Log.d("GuideSystem", "Received sample: ${sample.payload}")
        Log.d("GuideSystem", "Got payload: ${sample.payload.toBytes().decodeToString()}")
        if (sample.payload.toBytes().decodeToString() == "returnImageInfo") {
            sample.attachment?.toBytes()?.let {
                Log.d("GuideSystem", "Received image info: ${it.size} bytes")
                CoroutineScope(Dispatchers.Main).launch {
                    exoPlayer.playAudio(it)
                }
            }
        }
    })

    // Cleanup ExoPlayer on disposal
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
            Log.d("GuideSystemAudio", "ExoPlayer released")
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
                    sendToServer(pngBytes, zenoh, context)
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
            exoPlayer.playAudio(exampleData)
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
    onImageCaptured: (ByteArray) -> Unit // Callback with PNG bytes
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

// Suspended function to capture image and convert to PNG ByteArray
suspend fun captureImageAsPng(
    imageCapture: ImageCapture,
    executor: Executor
): ByteArray {
    Log.d("GuideSystemCamera", "captureImageAsPng called")
    val imageProxy = imageCapture.takePicture(executor)
    Log.d("GuideSystemCamera", "ImageProxy received (Format: ${imageProxy.format})")

    // IMPORTANT: Ensure imageProxy is closed after use!
    try {
        // Conversion logic depends heavily on imageProxy.format
        // Common format is YUV_420_888, which needs conversion.
        // If format is JPEG, conversion is simpler. Let's assume YUV for robustness.
        val bitmap = imageProxy.toBitmap() // Requires a conversion function

        // Compress the Bitmap to PNG format in memory
        val outputStream = ByteArrayOutputStream()
        // Use Dispatchers.IO for potentially blocking compression
        withContext(Dispatchers.IO) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream) // Quality 100 for PNG (lossless)
        }
        val pngBytes = outputStream.toByteArray()
        Log.d("GuideSystemCamera", "Image converted to PNG: ${pngBytes.size} bytes")
        return pngBytes
    } finally {
        imageProxy.close() // Crucial step to allow next capture
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

// Function to play audio bytes
@OptIn(UnstableApi::class)
fun ExoPlayer.playAudio(audioBytes: ByteArray) {
    try {
        Log.d("GuideSystemAudio", "Attempting to play audio (${audioBytes.size} bytes)")
        // 1. Create a DataSource.Factory that reads from the byte array
        val dataSourceFactory = DataSource.Factory {
            ByteArrayDataSource(audioBytes)
        }
        // 2. Create a MediaSource using this factory
        // Assuming WAV format, ProgressiveMediaSource should work.
        // You might need a more specific MediaSource if it's a different raw format.
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY)) // URI is dummy here

        // 3. Set the media source and prepare the player
        setMediaSource(mediaSource)
        prepare()
        // 4. Start playback
        playWhenReady = true
        Log.d("GuideSystemAudio", "Audio playback started")
    } catch (e: Exception) {
        Log.e("GuideSystemAudio", "Error playing audio: ${e.message}", e)
    }
}