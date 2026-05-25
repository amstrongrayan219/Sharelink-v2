package com.example.network

import android.content.Context
import android.util.Log

object HttpServerManager {
    private var server: LocalHttpServer? = null

    fun startServer(context: Context) {
        if (server == null) {
            try {
                server = LocalHttpServer(context.applicationContext, 8080)
                server?.start()
                Log.d("HttpServerManager", "Server started on port 8080")
            } catch (e: Exception) {
                Log.e("HttpServerManager", "Failed to start server: ${e.message}")
            }
        }
    }

    fun stopServer() {
        try {
            server?.stop()
            server = null
            Log.d("HttpServerManager", "Server stopped")
        } catch (e: Exception) {
            Log.e("HttpServerManager", "Failed to stop server: ${e.message}")
        }
    }
}
