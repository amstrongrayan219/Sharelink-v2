package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.model.DeviceConnection
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.PrefsManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold { innerPadding ->
                    ScannerScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        cameraExecutor = cameraExecutor,
                        onBackPressed = {
                            finish()
                        },
                        onDeviceConfirmed = { device ->
                            // Save to local connection history
                            PrefsManager(this).addToHistory(device)
                            
                            // Navigate immediately to TransferActivity
                            val intent = Intent(this, TransferActivity::class.java).apply {
                                putExtra("extra_device_name", device.name)
                                putExtra("extra_device_ip", device.ip)
                                putExtra("extra_device_http_port", device.http_port)
                                putExtra("extra_device_udp_port", device.udp_port)
                            }
                            startActivity(intent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun ScannerScreen(
    modifier: Modifier = Modifier,
    cameraExecutor: ExecutorService,
    onBackPressed: () -> Unit,
    onDeviceConfirmed: (DeviceConnection) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detectedDevice by remember { mutableStateOf<DeviceConnection?>(null) }
    var isProcessingScan by remember { mutableStateOf(true) }
    
    // Setup and bind camera preview & analysis
    Box(modifier = modifier) {
        if (isProcessingScan) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (!isProcessingScan) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            
                            @OptIn(ExperimentalGetImage::class)
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                val scanner = BarcodeScanning.getClient()
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            val rawValue = barcode.rawValue
                                            if (rawValue != null) {
                                                val parsedDevice = parseDeviceJson(rawValue)
                                                if (parsedDevice != null) {
                                                    isProcessingScan = false
                                                    detectedDevice = parsedDevice
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener {
                                        Log.e("ScannerActivity", "Barcode scanning failure", it)
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                        } catch (exc: Exception) {
                            Log.e("ScannerActivity", "Use case binding failed", exc)
                        }

                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder background while dialog is shown
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Animated laser HUD overlay
        ScannerHudOverlay(modifier = Modifier.fillMaxSize())

        // Header Instructions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 24.dp, end = 24.dp)
                .align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Scanner le code",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ajustez le code QR au PC dans la zone centrale",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Display results alert popup immediately once device QR is scanned
        detectedDevice?.let { device ->
            AlertDialog(
                onDismissRequest = {
                    detectedDevice = null
                    isProcessingScan = true
                },
                title = {
                    Text(
                        text = "Appareil détecté",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Voulez-vous vous connecter à cet ordinateur ?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF232D3F))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Nom : ${device.name}",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "IP : ${device.ip}",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Port HTTP : ${device.http_port}",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeviceConfirmed(device)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("popup_connect_button")
                    ) {
                        Text("Connecter", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            detectedDevice = null
                            isProcessingScan = true
                        },
                        modifier = Modifier.testTag("popup_cancel_button")
                    ) {
                        Text("Annuler", color = Color.Gray)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun ScannerHudOverlay(modifier: Modifier = Modifier) {
    val laserTransition = rememberInfiniteTransition(label = "Laser scanner transition")
    val laserYPercentage by laserTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Scanner laser offset animate"
    )

    Canvas(modifier = modifier) {
        val screenWidth = size.width
        val screenHeight = size.height
        val boxSideLength = screenWidth * 0.70f
        
        val left = (screenWidth - boxSideLength) / 2f
        val top = (screenHeight - boxSideLength) / 2f
        val right = left + boxSideLength
        val bottom = top + boxSideLength

        // Draw transparent dark mask outside camera box selection range
        drawWithLayer {
            // Fill entire view with semi-opaque black
            drawRect(Color.Black.copy(alpha = 0.55f))
            // Clear current central area 
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(boxSideLength, boxSideLength),
                cornerRadius = CornerRadius(16f, 16f),
                blendMode = BlendMode.Clear
            )
        }

        // Draw camera scanner box borders
        drawRoundRect(
            color = Color(0xFF1565C0),
            topLeft = Offset(left, top),
            size = Size(boxSideLength, boxSideLength),
            cornerRadius = CornerRadius(16f, 16f),
            style = Stroke(width = 4.dp.toPx())
        )

        // Draw animated search laser line
        val laserY = top + (boxSideLength * laserYPercentage)
        drawLine(
            color = Color(0xFF1565C0),
            start = Offset(left + 12f, laserY),
            end = Offset(right - 12f, laserY),
            strokeWidth = 3.dp.toPx()
        )
    }
}

// Ensure transparent layering fits on custom preview canvasing logic cleanly
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWithLayer(
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    with(drawContext.canvas) {
        val paint = androidx.compose.ui.graphics.Paint()
        saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint)
        block()
        restore()
    }
}

private fun parseDeviceJson(rawJson: String): DeviceConnection? {
    return try {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(DeviceConnection::class.java)
        adapter.fromJson(rawJson)
    } catch (e: Exception) {
        null
    }
}
