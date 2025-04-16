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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Callback interface now needs to potentially handle the signal on success
    interface UploadCallbacks {
        // Pass signal to onSuccess so it can be forwarded to the audio player
        fun onSuccess(responseBody: ByteArray, cycleCompletionSignal: CompletableDeferred<Unit>)
        fun onFailure(e: IOException)
        fun onError(responseCode: Int, errorMessage: String?)
    }

    // Modified function signature to accept the signal
    fun uploadImage(
        imageData: ByteArray,
        imageMediaType: String,
        callbacks: UploadCallbacks,
        cycleCompletionSignal: CompletableDeferred<Unit> // Added parameter
    ) {
        val url = "http://10.1.1.2:7447/uploadImage"

        val mediaType = imageMediaType.toMediaTypeOrNull()
        if (mediaType == null) {
            val error = IOException("Invalid media type: $imageMediaType")
            callbacks.onFailure(error)
            // Complete signal immediately on this type of setup failure
            cycleCompletionSignal.complete(Unit)
            Log.d("ApiClient", "Cycle completion signalled (invalid media type)")
            return
        }
        val requestBody: RequestBody = imageData.toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiClient", "Upload failed", e)
                callbacks.onFailure(e) // Call original callback
                // Signal completion on network failure
                cycleCompletionSignal.complete(Unit)
                Log.d("ApiClient", "Cycle completion signalled (onFailure)")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val responseCode = response.code
                        val errorBody = it.body?.string()
                        Log.e("ApiClient", "Upload request failed with code: $responseCode, body: $errorBody")
                        callbacks.onError(responseCode, errorBody) // Call original callback
                        // Signal completion on HTTP error
                        cycleCompletionSignal.complete(Unit)
                        Log.d("ApiClient", "Cycle completion signalled (onError)")
                    } else {
                        val responseBody = it.body?.bytes()
                        if (responseBody != null) {
                            Log.d("ApiClient", "Upload successful: ${response.code}")
                            // IMPORTANT: Pass the signal to the onSuccess callback
                            // It's the responsibility of the onSuccess implementation
                            // (specifically the audio player) to complete it later.
                            callbacks.onSuccess(responseBody, cycleCompletionSignal)
                        } else {
                            val error = IOException("Received null response body")
                            Log.e("ApiClient", "Upload successful but response body was null")
                            callbacks.onFailure(error) // Call original callback (or maybe onError?)
                            // Signal completion if response body is null (treat as failure)
                            cycleCompletionSignal.complete(Unit)
                            Log.d("ApiClient", "Cycle completion signalled (null response body)")
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
    var isSwitchedOn by remember { mutableStateOf(false) }
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val audioPlayer = remember { AudioPlayer(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.releaseMediaPlayer()
            AudioPlayer.AudioState.setPlaying(false) // Ensure state reset on dispose
            Log.d("GuideSystemAudio", "GuideSystemScreen disposed, AudioPlayer released")
        }
    }

    CameraCaptureEffect(
        isSwitchedOn = isSwitchedOn && cameraPermissionState.status.isGranted,
        context = context,
        lifecycleOwner = lifecycleOwner,
        onImageCaptured = { imageBytes, cycleCompletionSignal ->
            scope.launch {
                try {
                    // Launch network request on IO dispatcher from the current scope
                    withContext(Dispatchers.IO) {
                        Log.d("GuideSystem", "Initiating image upload (${imageBytes.size} bytes)...")
                        ApiClient.uploadImage(
                            imageBytes, // Pass the original bytes from capture
                            "image/jpeg", // Assuming captureImageAsJpeg produces JPEG
                            object : ApiClient.UploadCallbacks {
                                override fun onSuccess(responseBody: ByteArray, receivedSignal: CompletableDeferred<Unit>) {
                                    Log.d("GuideSystem", "Image upload successful, received ${responseBody.size} audio bytes.")
                                    // Launch the suspend function playAudio using the scope
                                    scope.launch { // Use the composable's scope
                                        Log.d("GuideSystem", "Calling audioPlayer.playAudio...")
                                        // Call the suspend function directly
                                        audioPlayer.playAudio(responseBody, receivedSignal)
                                        // No need to switch context here, playAudio handles its own context switching
                                        Log.d("GuideSystem", "audioPlayer.playAudio call returned.") // Indicates playAudio finished its logic
                                    }
                                }

                                override fun onFailure(e: IOException) {
                                    Log.e("GuideSystem", "Image upload failed: ${e.message}", e)
                                    // Signal is completed by ApiClient.uploadImage
                                }

                                override fun onError(responseCode: Int, errorMessage: String?) {
                                    Log.e("GuideSystem", "Image upload error (code $responseCode): $errorMessage")
                                    // Signal is completed by ApiClient.uploadImage
                                }
                            },
                            cycleCompletionSignal // Pass the signal here
                        )
                    } // End IO Context for ApiClient call
                } catch (e: Exception) {
                    Log.e("GuideSystem", "Error during upload initiation or processing: ${e.message}", e)
                    if (!cycleCompletionSignal.isCompleted) {
                        cycleCompletionSignal.complete(Unit) // Ensure signal completion on error
                        Log.d("GuideSystem", "Cycle completion signalled (onImageCaptured exception)")
                    }
                }
            }
        }
    )

    // --- UI Layout remains the same ---
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
                    cameraPermissionState.launchPermissionRequest()
                } else if (!newValue) {
                    // Optional: Explicitly stop audio if user switches off
                    audioPlayer.releaseMediaPlayer()
                    AudioPlayer.AudioState.setPlaying(false)
                    Log.d("GuideSystem", "Switch turned off, stopped audio playback.")
                }
                Log.d("GuideSystem", "Switch toggled: $newValue")
            },
            enabled = true // Or based on camera permission potentially
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
        Button(onClick = {
            Log.d("GuideSystemAudio", "Test Audio Button Clicked")
            // Test button doesn't participate in the main loop signalling
            val exampleData = runCatching {
                val audioResId = R.raw.sample // Make sure this resource exists
                context.resources.openRawResource(audioResId).use { input ->
                    ByteArrayOutputStream().use { output ->
                        input.copyTo(output)
                        output.toByteArray()
                    }
                }
            }.getOrElse {
                Log.e("GuideSystemAudio", "Error loading sample audio", it)
                null // Handle error case
            }

            if (exampleData != null) {
                // Create a dummy signal for the test button, it won't affect the main loop
                val dummySignal = CompletableDeferred<Unit>()
                // Add a completion handler just for logging/debugging the test
                dummySignal.invokeOnCompletion { cause ->
                    if (cause == null) {
                        Log.d("GuideSystemAudio", "Test audio dummy signal completed.")
                    } else {
                        Log.e("GuideSystemAudio", "Test audio dummy signal completed with error", cause)
                    }
                }
                Log.d("GuideSystemAudio", "Playing test audio...")
                CoroutineScope(Dispatchers.Main).launch {
                    audioPlayer.playAudio(exampleData, dummySignal)
                }
            } else {
                Log.e("GuideSystemAudio", "Cannot play test audio, data is null.")
                // Optionally show a Toast message to the user
            }
        }) {
            Text("Test Audio Playback")
        }
    }
}

@Composable
fun CameraCaptureEffect(
    isSwitchedOn: Boolean,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onImageCaptured: suspend CoroutineScope.(imageBytes: ByteArray, cycleCompletionSignal: CompletableDeferred<Unit>) -> Unit,
) {
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // Effect to get CameraProvider remains the same
    LaunchedEffect(Unit) {
        try {
            cameraProvider = context.getCameraProvider()
            Log.d("GuideSystemCamera", "CameraProvider obtained")
        } catch (e: Exception) {
            Log.e("GuideSystemCamera", "Failed to get CameraProvider", e)
            // Handle error appropriately - maybe disable switch, show message
        }
    }

    // The main camera binding and capture loop effect
    LaunchedEffect(isSwitchedOn, cameraProvider, lifecycleOwner) {
        if (isSwitchedOn && cameraProvider != null) {
            Log.d("GuideSystemCamera", "Setting up camera capture loop...")
            val currentCameraProvider = cameraProvider ?: return@LaunchedEffect
            var boundImageCapture: ImageCapture? = null // Local var for safety within the loop setup

            try {
                currentCameraProvider.unbindAll() // Start clean
                val localImageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    // Add other configurations like target resolution if needed
                    .build()
                boundImageCapture = localImageCapture // Assign to local var
                imageCapture = localImageCapture // Update state if needed elsewhere

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                // Bind to lifecycle within the coroutine scope
                currentCameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, localImageCapture)
                Log.d("GuideSystemCamera", "Camera bound to lifecycle")

                // --- THE CORE LOOP CHANGE ---
                while (isActive && isSwitchedOn) {
                    // 1. Check if audio is currently playing. If so, wait.
                    while (AudioPlayer.AudioState.isPlaying() && isActive) {
                        Log.d("GuideSystemCamera", "Waiting for audio playback to finish...")
                        delay(500) // Check every 500ms
                    }
                    // Re-check isActive and isSwitchedOn after potential delay
                    if (!isActive || !isSwitchedOn) break

                    // 2. Create the completion signal for this specific cycle
                    val cycleCompletionSignal = CompletableDeferred<Unit>()
                    Log.d("GuideSystemCamera", "Initiating new image capture cycle...")

                    try {
                        // 3. Capture the image
                        Log.d("GuideSystemCamera", "Taking picture...")
                        val imgBytes = captureImageAsJpeg(localImageCapture, cameraExecutor) // Use bound instance
                        Log.d("GuideSystemCamera", "Picture taken (${imgBytes.size} bytes). Processing...")

                        // 4. Launch the processing/upload lambda (asynchronously)
                        // Pass the signal. Use 'this' scope from LaunchedEffect.
                        // The lambda itself will switch to Dispatchers.IO internally.
                        onImageCaptured(imgBytes, cycleCompletionSignal)
                        Log.d("GuideSystemCamera", "onImageCaptured lambda launched. Waiting for cycle completion signal...")

                        // 5. Wait here until the cycle is signalled as complete
                        // Add a timeout to prevent waiting forever if something goes wrong
                        try {
                            withTimeout(60_000L) {
                                cycleCompletionSignal.await()
                            }
                            Log.d("GuideSystemCamera", "Cycle completion signal received. Proceeding to next cycle.")
                        } catch (e: TimeoutCancellationException) {
                            Log.e("GuideSystemCamera", "Cycle timed out after 60 seconds! Completing signal to proceed.", e)
                            // Ensure signal is completed so loop doesn't hang, even on timeout
                            if (!cycleCompletionSignal.isCompleted) {
                                cycleCompletionSignal.completeExceptionally(e) // Complete with exception
                            }
                            // Consider adding a longer delay here before retrying
                            delay(5000)
                        }

                    } catch (e: ImageCaptureException) {
                        Log.e("GuideSystemCamera", "Image capture failed", e)
                        // Ensure signal is completed so await() doesn't hang
                        if (!cycleCompletionSignal.isCompleted) {
                            cycleCompletionSignal.completeExceptionally(e)
                            Log.d("GuideSystemCamera", "Cycle completion signalled (ImageCaptureException)")
                        }
                        // Wait a bit before retrying after a capture error
                        delay(2000) // e.g., 2 seconds
                    } catch (e: CancellationException) {
                        // Coroutine was cancelled (e.g., screen disposed, switch turned off)
                        Log.d("GuideSystemCamera", "Capture loop cancelled.", e)
                        if (!cycleCompletionSignal.isCompleted) {
                            cycleCompletionSignal.cancel() // Propagate cancellation
                        }
                        throw e // Re-throw cancellation
                    } catch (e: Exception) {
                        Log.e("GuideSystemCamera", "Unexpected error during capture/processing setup", e)
                        // Ensure signal is completed
                        if (!cycleCompletionSignal.isCompleted) {
                            cycleCompletionSignal.completeExceptionally(e)
                            Log.d("GuideSystemCamera", "Cycle completion signalled (Unexpected Exception)")
                        }
                        // Wait before retry after other errors
                        delay(5000) // e.g., 5 seconds
                    }
                    // Optional small delay between cycles, even after signal completion,
                    // can prevent overly rapid captures if signals complete very fast.
                    // delay(100) // e.g., 100ms buffer
                }
                // End of while loop

            } catch (exc: Exception) {
                // Catch errors during binding setup
                Log.e("GuideSystemCamera", "Use case binding or capture loop setup failed", exc)
                // Potentially update UI or state to indicate camera failure
            } finally {
                Log.d("GuideSystemCamera", "Exiting capture loop or setup failed. Unbinding camera.")
                // Ensure camera is unbound when loop exits (due to !isSwitchedOn, !isActive, or error)
                // or if setup fails.
                cameraProvider?.unbindAll()
                imageCapture = null // Clear reference
            }
        } else {
            // Conditions for running are not met (switched off or provider not ready)
            Log.d("GuideSystemCamera", "Capture conditions not met (isSwitchedOn=$isSwitchedOn, cameraProvider!=null is ${cameraProvider!=null}). Ensuring camera is unbound.")
            cameraProvider?.unbindAll() // Ensure unbinding when switched off or provider lost
            imageCapture = null
        }
    }

    // Cleanup DisposableEffect remains the same
    DisposableEffect(lifecycleOwner) {
        onDispose {
            Log.d("GuideSystemCamera", "Disposing CameraCaptureEffect. Shutting down executor and unbinding.")
            cameraExecutor.shutdown()
            cameraProvider?.unbindAll()
            // Ensure audio stops if the composable is disposed
            // (Though the screen-level DisposableEffect also handles this)
            // audioPlayer?.releaseMediaPlayer() // Assuming audioPlayer is accessible or passed down
        }
    }
}

suspend fun captureImageAsJpeg(
    imageCapture: ImageCapture,
    executor: Executor
): ByteArray {
    Log.d("GuideSystemCamera", "captureImageAsJpeg called")
    val imageProxy = imageCapture.takePicture(executor)
    Log.d("GuideSystemCamera", "ImageProxy received (Format: ${imageProxy.format})") // Format might be YUV_420_888 initially

    try {
        // Convert ImageProxy (often YUV) to Bitmap first
        // Note: imageProxy.toBitmap() can be memory intensive for large images.
        // Consider alternatives like converting YUV directly to JPEG if performance is critical.
        val bitmap = imageProxy.toBitmap() // This step decodes the image data

        // Compress the Bitmap to JPEG format in memory
        val outputStream = ByteArrayOutputStream()
        // Use Dispatchers.Default for CPU-bound compression work
        withContext(Dispatchers.Default) { // Use Default for CPU work
            // Adjust quality (e.g., 85) as needed for balance
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        }
        val jpegBytes = outputStream.toByteArray()
        Log.d("GuideSystemCamera", "Image converted to JPEG: ${jpegBytes.size} bytes")

        // Explicitly recycle the bitmap to free memory immediately
        // Crucial when dealing with Bitmaps in loops
        bitmap.recycle()

        return jpegBytes
    } finally {
        // IMPORTANT: Close the ImageProxy to release the underlying image buffer
        imageProxy.close()
        Log.d("GuideSystemCamera", "ImageProxy closed")
    }
}

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
    private var currentTempFile: File? = null

    // AudioState remains the same
    object AudioState {
        private val isPlayingAudio = AtomicBoolean(false)
        fun setPlaying(playing: Boolean) { isPlayingAudio.set(playing) }
        fun isPlaying(): Boolean = isPlayingAudio.get()
    }

    // Make playAudio a suspend function
    suspend fun playAudio(audioBytes: ByteArray, cycleCompletionSignal: CompletableDeferred<Unit>) {
        // Check if signal is already completed (good practice)
        if (cycleCompletionSignal.isCompleted) {
            Log.w("AudioPlayer", "playAudio called but cycleCompletionSignal is already completed.")
            return // Don't proceed if the cycle is already marked as finished
        }

        var tempFile: File? = null // Use local variable for safety

        try {
            // --- IO Operations ---
            withContext(Dispatchers.IO) {
                Log.d("AudioPlayer", "Preparing audio (${audioBytes.size} bytes) on IO thread")
                // Release any previous player *before* potentially blocking IO
                // Ensure this is done on the Main thread though
                withContext(Dispatchers.Main) {
                    releaseMediaPlayer() // Release previous player safely on Main thread
                }

                tempFile = File.createTempFile("audio", ".wav", context.cacheDir).also {
                    currentTempFile = it // Keep track for deletion
                    FileOutputStream(it).use { fos -> fos.write(audioBytes) }
                    Log.d("AudioPlayer", "Audio saved to temporary file: ${it.path}")
                }
            } // End IO Context

            // --- MediaPlayer Operations on Main Thread ---
            // Use suspendCoroutine to bridge callback-based MediaPlayer setup with coroutines
            // ensuring we wait for completion or error before proceeding/completing signal
            suspendCoroutine<Unit> { continuation ->
                // Ensure we are on the Main thread for MediaPlayer interactions
                CoroutineScope(Dispatchers.Main).launch {
                    val fileToPlay = tempFile
                    if (fileToPlay == null || !fileToPlay.exists()) {
                        Log.e("AudioPlayer", "Temporary audio file is null or does not exist.")
                        AudioState.setPlaying(false)
                        // Complete signal here as playback cannot proceed
                        if (!cycleCompletionSignal.isCompleted) cycleCompletionSignal.complete(Unit)
                        Log.d("AudioPlayer", "Cycle completion signalled (temp file missing)")
                        continuation.resumeWithException(IOException("Temporary audio file missing"))
                        return@launch
                    }

                    try {
                        AudioState.setPlaying(true)
                        Log.d("AudioPlayer", "Audio playback state set to PLAYING")

                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(fileToPlay.path)

                            setOnCompletionListener { mp ->
                                Log.d("AudioPlayer", "Playback completed")
                                AudioState.setPlaying(false)
                                Log.d("AudioPlayer", "Audio playback state set to NOT PLAYING")
                                // Release resources *after* completion
                                releaseMediaPlayer()
                                CoroutineScope (Dispatchers.Main).launch {
                                    delay(5_000)
                                    if (!cycleCompletionSignal.isCompleted) cycleCompletionSignal.complete(Unit)
                                }
                                Log.d("AudioPlayer", "Cycle completion signalled (onComplete)")
                                continuation.resume(Unit) // Resume the suspendCoroutine
                            }

                            setOnErrorListener { mp, what, extra ->
                                Log.e("AudioPlayer", "MediaPlayer error: what=$what, extra=$extra")
                                AudioState.setPlaying(false)
                                Log.d("AudioPlayer", "Audio playback state set to NOT PLAYING on error")
                                // Release resources on error
                                releaseMediaPlayer()
                                // Complete the signal and resume the coroutine with an exception
                                val error = IOException("MediaPlayer error: what=$what, extra=$extra")
                                if (!cycleCompletionSignal.isCompleted) cycleCompletionSignal.complete(Unit)
                                Log.d("AudioPlayer", "Cycle completion signalled (onError)")
                                continuation.resumeWithException(error) // Resume with exception
                                true // Indicate error was handled
                            }

                            setOnPreparedListener { mp ->
                                Log.d("AudioPlayer", "MediaPlayer prepared, starting playback.")
                                try {
                                    mp.start()
                                    Log.d("AudioPlayer", "Audio playback started")
                                    // Don't resume continuation here, wait for onCompletion or onError
                                } catch (e: IllegalStateException) {
                                    Log.e("AudioPlayer", "MediaPlayer start error: ${e.message}", e)
                                    AudioState.setPlaying(false)
                                    releaseMediaPlayer()
                                    if (!cycleCompletionSignal.isCompleted) cycleCompletionSignal.complete(Unit)
                                    Log.d("AudioPlayer", "Cycle completion signalled (start error)")
                                    continuation.resumeWithException(e)
                                }
                            }

                            Log.d("AudioPlayer", "Preparing MediaPlayer asynchronously...")
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        // Catch setup errors (e.g., setDataSource)
                        Log.e("AudioPlayer", "Error setting up MediaPlayer: ${e.message}", e)
                        AudioState.setPlaying(false)
                        releaseMediaPlayer() // Clean up on setup error
                        // Complete the signal and resume the coroutine with an exception
                        if (!cycleCompletionSignal.isCompleted) cycleCompletionSignal.complete(Unit)
                        Log.d("AudioPlayer", "Cycle completion signalled (MediaPlayer setup exception)")
                        continuation.resumeWithException(e) // Resume with exception
                    }
                }
            }
        } catch (e: Exception) {
            // Catch errors from IO operations or MediaPlayer setup/playback (rethrown)
            Log.e("AudioPlayer", "Error during audio playback process: ${e.message}", e)
            AudioState.setPlaying(false)
            Log.d("AudioPlayer", "Audio playback state set to NOT PLAYING on exception")
            // Ensure cleanup happens on the Main thread
            withContext(Dispatchers.Main.immediate) { // Use immediate to avoid delay if already on Main
                releaseMediaPlayer()
            }
            // Ensure signal is completed if an error occurred
            if (!cycleCompletionSignal.isCompleted) {
                cycleCompletionSignal.complete(Unit) // Complete normally to unblock loop
                Log.d("AudioPlayer", "Cycle completion signalled (outer catch block)")
            }
        }
        // No finally block needed for signal completion as it's handled in all paths (completion, error, exception)
    }

    // releaseMediaPlayer remains largely the same, ensure it's called on Main thread
    // Make it callable from any thread by switching context internally if needed,
    // or ensure callers always call it from Main. Let's ensure callers handle it.
    // Added check for mediaPlayer nullity before accessing.
    fun releaseMediaPlayer() {
        // Best practice: Ensure this runs on the Main thread if called from background
        // However, in our refactored playAudio, it's called within withContext(Dispatchers.Main)
        // or from listeners which are invoked on Main thread.
        val player = mediaPlayer ?: return // Return if already null
        mediaPlayer = null // Set to null early to prevent race conditions

        player.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.setOnPreparedListener(null)
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
            } catch (e: IllegalStateException) {
                Log.w("AudioPlayer", "MediaPlayer stop/reset error: ${e.message}")
                try { it.reset() } catch (_: IllegalStateException) { /* ignore secondary error */ }
            } finally {
                it.release()
                Log.d("AudioPlayer", "MediaPlayer released")
            }
        }

        // Delete temp file (can be done from any thread)
        currentTempFile?.let { file ->
            // Optionally run deletion on IO dispatcher if concerned about blocking Main thread
            // CoroutineScope(Dispatchers.IO).launch { ... }
            // But delete is usually fast enough.
            if (file.exists()) {
                if (file.delete()) {
                    Log.d("AudioPlayer", "Temporary audio file deleted: ${file.path}")
                } else {
                    Log.w("AudioPlayer", "Failed to delete temporary audio file: ${file.path}")
                }
            }
            currentTempFile = null
        }
        // State is set to false by listeners/errors before release is called
    }
}