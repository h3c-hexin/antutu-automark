package com.autorun.antutu

import android.os.Handler
import android.os.Looper
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FeishuUploader {

    private const val WEBHOOK_URL =
        "https://jmsvx9wiyv.feishu.cn/base/automation/webhook/event/WWpTaMTF8wd4SQhIGbSc1HZdnWd"
    private const val MAX_RETRY = 3

    private val handler = Handler(Looper.getMainLooper())

    fun upload(name: String, data: String, time: String, callback: (Boolean) -> Unit) {
        Thread {
            var success = false
            for (i in 1..MAX_RETRY) {
                try {
                    val conn = URL(WEBHOOK_URL).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.doOutput = true

                    val json = """{"name":"$name","data":"$data","time":"$time"}"""
                    OutputStreamWriter(conn.outputStream).use { it.write(json) }

                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    if (code == 200 && body.contains("\"code\":0")) {
                        success = true
                        break
                    }
                } catch (_: Exception) {
                }
                if (i < MAX_RETRY) Thread.sleep(2000)
            }
            val result = success
            handler.post { callback(result) }
        }.start()
    }
}
