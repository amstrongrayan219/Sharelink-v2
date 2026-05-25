package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import com.example.utils.PrefsManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeviceConnection
import com.example.model.ReceivedFile
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.TransferHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val sizeString: String
)

class TransferActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val deviceName = intent.getStringExtra("extra_device_name") ?: "PC"
        val deviceIp = intent.getStringExtra("extra_device_ip") ?: "127.0.0.1"
        val httpPort = intent.getIntExtra("extra_device_http_port", 8080)
        val udpPort = intent.getIntExtra("extra_device_udp_port", 8081)

        val device = DeviceConnection(deviceName, deviceIp, httpPort, udpPort)

        // Load existing received files
        TransferHistoryManager.loadReceivedFiles(this)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TransferScreen(
                    device = device,
                    onBackPressed = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    device: DeviceConnection,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Loaded received files list
    val receivedFiles by TransferHistoryManager.receivedFiles.collectAsState()

    // Selected files list
    val selectedFiles = remember { mutableStateListOf<SelectedFile>() }

    // Upload status states
    var isSending by remember { mutableStateOf(false) }
    var sendProgressCurrent by remember { mutableStateOf(0) }
    var sendProgressMax by remember { mutableStateOf(0) }
    var currentSendingFileName by remember { mutableStateOf("") }
    var currentSendingFileSizeStr by remember { mutableStateOf("") }
    var transferStatusDesc by remember { mutableStateOf("") }

    // Pulsing animation for the green connection indicator
    val connectionPulseAttr = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by connectionPulseAttr.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha animate"
    )

    // Select files picker launcher
    val selectFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris ->
            uris.forEach { uri ->
                val details = getUriDetails(context, uri)
                // Avoid redundant selections
                if (selectedFiles.none { it.uri == uri }) {
                    selectedFiles.add(
                        SelectedFile(
                            uri = uri,
                            name = details.first,
                            size = details.second,
                            sizeString = formatFileSize(details.second)
                        )
                    )
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Connecté à : ",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF2E7D32) // Success Green
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Connection green active dot
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .alpha(dotAlpha)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E7D32))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${device.ip}:${device.http_port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Retour",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            Color(0xFF0F1B2B)
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // SECTION 1: ENVOYER (SEND SECTION)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header Send Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Envoi",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ENVOYER",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        
                        // Pick files button
                        OutlinedButton(
                            onClick = {
                                if (!isSending) {
                                    selectFilesLauncher.launch("*/*")
                                }
                            },
                            enabled = !isSending,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("add_files_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add files icon",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ajouter fichiers", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // List of selected files
                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedFiles.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Aucun fichier sélectionné.\nAppuyez sur 'Ajouter fichiers' pour commencer.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(selectedFiles) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF232D3F))
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Attachment,
                                                contentDescription = "Fichier",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = file.sizeString,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                        if (!isSending) {
                                            IconButton(
                                                onClick = { selectedFiles.remove(file) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete selection",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Progress bar and active status info
                    if (isSending || sendProgressCurrent > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Fichier $sendProgressCurrent sur $sendProgressMax",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${((sendProgressCurrent.toFloat() / sendProgressMax) * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            LinearProgressIndicator(
                                progress = {
                                    if (sendProgressMax > 0) sendProgressCurrent.toFloat() / sendProgressMax else 0f
                                },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Gray.copy(alpha = 0.3f)
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Text(
                                text = "Envoi : $currentSendingFileName ($currentSendingFileSizeStr)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Send Action Button
                    Button(
                        onClick = {
                            if (selectedFiles.isNotEmpty() && !isSending) {
                                isSending = true
                                sendProgressMax = selectedFiles.size
                                sendProgressCurrent = 0
                                coroutineScope.launch {
                                    performFileTransfer(
                                        context = context,
                                        device = device,
                                        files = selectedFiles.toList(),
                                        onProgressUpdate = { current, activeFile ->
                                            sendProgressCurrent = current
                                            currentSendingFileName = activeFile.name
                                            currentSendingFileSizeStr = activeFile.sizeString
                                        },
                                        onFinish = { success, msg ->
                                            isSending = false
                                            if (success) {
                                                selectedFiles.clear()
                                                Toast.makeText(context, "Tous les fichiers ont été envoyés avec succès !", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Échec de l'envoi : $msg", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        enabled = selectedFiles.isNotEmpty() && !isSending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("send_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send file Icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Envoyer", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // SECTION 2: RECEVOIR (RECEIVE SECTION)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    
                    // Header Receive Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Réception",
                                tint = Color(0xFF2E7D32), // Success Green
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RECEVOIR",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        // Open Folder Button
                        Button(
                            onClick = {
                                openDownloadsFolder(context)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B365D),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("open_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Open folder icon",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ouvrir dossier", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Received Files list (flows automatically in real time!)
                    Box(modifier = Modifier.weight(1f)) {
                        if (receivedFiles.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Aucun fichier reçu pour le moment.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(receivedFiles) { received ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1B2E1E)) // Subtle dark green tint
                                            .padding(10.dp)
                                            .clickable {
                                                openReceivedFile(context, received)
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Success",
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = received.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${received.sizeString} • Reçu à ${received.timeString}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = "Ouvrir",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getUriDetails(context: Context, uri: Uri): Pair<String, Long> {
    var name = "Fichier"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        // Fallback name parsing from Uri path
        uri.path?.let {
            val lastSlash = it.lastIndexOf('/')
            if (lastSlash != -1) {
                name = it.substring(lastSlash + 1)
            }
        }
    }
    return Pair(name, size)
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    val index = if (digitGroups < units.size) digitGroups else units.size - 1
    return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, index.toDouble()), units[index])
}

// Perform POST operations to PC with OkHttp
private suspend fun performFileTransfer(
    context: Context,
    device: DeviceConnection,
    files: List<SelectedFile>,
    onProgressUpdate: (Int, SelectedFile) -> Unit,
    onFinish: (Boolean, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.MINUTES)
            .build()
        
        val deviceNameLocal = PrefsManager(context).deviceName

        // Step 1: POST request-transfer
        try {
            val jsonPayload = JSONObject().apply {
                put("name", deviceNameLocal)
                put("files", org.json.JSONArray(files.map { it.name }))
                put("port", 8080)
            }

            val requestInit = Request.Builder()
                .url("http://${device.ip}:${device.http_port}/request-transfer")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            val responseInit = client.newCall(requestInit).execute()
            val bodyInit = responseInit.body?.string() ?: ""
            responseInit.close()
            
            if (!responseInit.isSuccessful) {
                withContext(Dispatchers.Main) {
                    onFinish(false, "Le PC a renvoyé un code HTTP ${responseInit.code}")
                }
                return@withContext
            }

            val accepted = try {
                JSONObject(bodyInit).getBoolean("accepted")
            } catch (je: Exception) {
                false
            }

            if (!accepted) {
                withContext(Dispatchers.Main) {
                    onFinish(false, "Le transfert a été refusé par le PC.")
                }
                return@withContext
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onFinish(false, "Impossible de contacter le PC (${e.localizedMessage}). Vérifiez si le script PC est actif et si vous êtes connecté au même réseau.")
            }
            return@withContext
        }

        // Step 2: Upload each file sequentially
        for (i in files.indices) {
            val fileItem = files[i]

            withContext(Dispatchers.Main) {
                onProgressUpdate(i + 1, fileItem)
            }

            try {
                // Read Uri data using custom RequestBody streaming to support any content Uri cleanly
                val customRequestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = fileItem.size
                    override fun writeTo(sink: BufferedSink) {
                        context.contentResolver.openInputStream(fileItem.uri)?.use { inputStream ->
                            val buffer = ByteArray(32768)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                            }
                        } ?: throw IOException("Impossible de lire l'uri")
                    }
                }

                val requestUpload = Request.Builder()
                    .url("http://${device.ip}:${device.http_port}/upload")
                    .header("x-filename", fileItem.name)
                    .post(customRequestBody)
                    .build()

                val responseUpload = client.newCall(requestUpload).execute()
                val bodyStr = responseUpload.body?.string() ?: ""
                responseUpload.close()

                if (!responseUpload.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onFinish(false, "Échec HTTP lors de l'envoi de ${fileItem.name} : ${responseUpload.code}")
                    }
                    return@withContext
                }

                val success = try {
                    JSONObject(bodyStr).getBoolean("success")
                } catch (je: Exception) {
                    false
                }

                if (!success) {
                    withContext(Dispatchers.Main) {
                        onFinish(false, "Le PC a échoué à enregistrer le fichier ${fileItem.name}.")
                    }
                    return@withContext
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFinish(false, "Erreur pendant l'envoi de ${fileItem.name} : ${e.localizedMessage}")
                }
                return@withContext
            }
        }

        withContext(Dispatchers.Main) {
            onFinish(true, "Succès")
        }
    }
}

private fun openDownloadsFolder(context: Context) {
    try {
        val folder = File(context.getExternalFilesDir(null), "ShareLink")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val textLoc = "Dossier : Android/data/com.sharelink.app/files/ShareLink/"
        Toast.makeText(context, textLoc, Toast.LENGTH_LONG).show()

        // Attempt opening with system view intent 
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            setDataAndType(Uri.fromFile(folder), "*/*")
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        context.startActivity(Intent.createChooser(intent, "Ouvrir dossier ShareLink"))
    } catch (e: Exception) {
        Log.e("TransferActivity", "Unable to open folder", e)
    }
}

private fun openReceivedFile(context: Context, received: ReceivedFile) {
    try {
        val file = File(received.path)
        if (!file.exists()) {
            Toast.makeText(context, "Le fichier n'existe plus.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Ouverture de ${received.name}...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Erreur d'ouverture : ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
