package com.atar.cameraflipper

import android.media.Image
import android.os.Environment
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class ImageSaver(
    private val mImage: Image
) : Runnable {

    override fun run() {
        val file = File(FILE_PIC_DIR, PIC_FILE_NAME)
        file.parentFile.mkdir()
        val buffer = mImage.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(file).apply {
                write(bytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        } finally {
            mImage.close()
            output?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImageSaver"
        private val FILE_PIC_DIR = "${Environment.getExternalStorageDirectory()}${File.separator}CameraFilpper"
        private const val PIC_FILE_NAME = "camera_flipper_demo_pic.jpg"
    }
}