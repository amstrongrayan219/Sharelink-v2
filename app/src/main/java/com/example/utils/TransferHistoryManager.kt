package com.example.utils

import android.content.Context
import com.example.model.ReceivedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransferHistoryManager {
    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFiles = _receivedFiles.asStateFlow()

    fun loadReceivedFiles(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "ShareLink")
        if (!dir.exists()) {
            dir.mkdirs()
            _receivedFiles.value = emptyList()
            return
        }
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val filesList = dir.listFiles()?.filter { it.isFile }?.map { file ->
            ReceivedFile(
                name = file.name,
                sizeString = formatFileSize(file.length()),
                timeString = sdf.format(Date(file.lastModified())),
                path = file.absolutePath
            )
        }?.sortedByDescending { File(it.path).lastModified() } ?: emptyList()
        _receivedFiles.value = filesList
    }

    fun addReceivedFile(file: File) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val newItem = ReceivedFile(
            name = file.name,
            sizeString = formatFileSize(file.length()),
            timeString = sdf.format(Date()),
            path = file.absolutePath
        )
        val currentList = _receivedFiles.value.toMutableList()
        currentList.removeAll { it.path == file.absolutePath }
        currentList.add(0, newItem)
        _receivedFiles.value = currentList
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups < units.size) digitGroups else units.size - 1
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, index.toDouble()), units[index])
    }
}
