package com.example.network

import android.content.Context
import com.example.utils.TransferHistoryManager
import fi.iki.elonen.NanoHTTPD
import java.io.File

class LocalHttpServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri == "/request-transfer" && method == Method.POST) {
            val filesMap = HashMap<String, String>()
            try {
                session.parseBody(filesMap)
                return newFixedLengthResponse(
                    Response.Status.OK, 
                    "application/json", 
                    "{\"accepted\":true}"
                )
            } catch (e: Exception) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, 
                    "text/plain", 
                    "Error request-transfer: ${e.message}"
                )
            }
        }

        if (uri == "/upload" && method == Method.POST) {
            val headers = session.headers
            val filename = headers["x-filename"] ?: "upload_${System.currentTimeMillis()}"
            val filesMap = HashMap<String, String>()
            try {
                session.parseBody(filesMap)
                val tempPath = filesMap["content"] ?: filesMap["postData"]
                if (tempPath != null) {
                    val srcFile = File(tempPath)
                    val parentFolder = File(context.getExternalFilesDir(null), "ShareLink")
                    if (!parentFolder.exists()) {
                        parentFolder.mkdirs()
                    }
                    val targetFile = File(parentFolder, filename)
                    srcFile.copyTo(targetFile, overwrite = true)
                    
                    TransferHistoryManager.addReceivedFile(targetFile)
                    
                    return newFixedLengthResponse(
                        Response.Status.OK, 
                        "application/json", 
                        "{\"success\":true}"
                    )
                } else {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, 
                        "text/plain", 
                        "Payload has empty content path"
                    )
                }
            } catch (e: Exception) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, 
                    "text/plain", 
                    "Error upload: ${e.message}"
                )
            }
        }

        return newFixedLengthResponse(
            Response.Status.NOT_FOUND, 
            "text/plain", 
            "La route demandée n'existe pas."
        )
    }
}
