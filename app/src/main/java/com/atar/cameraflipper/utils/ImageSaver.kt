package com.atar.cameraflipper.utils

import android.media.Image
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference

internal class ImageSaver(
    private val mImage: Image,
    private val mFile: File,
    imageSavedListener: ImageSavedListener
) : Runnable {

    private val mImageSavedListener: WeakReference<ImageSavedListener> =
        WeakReference(imageSavedListener)

    override fun run() {
        val buffer = mImage.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(mFile).apply {
                write(bytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        } finally {
            mImage.close()
            output?.let {
                try {
                    it.close()
                    mImageSavedListener.get()?.onImageSaved()
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                }
            }
        }
    }

    interface ImageSavedListener {
        fun onImageSaved()
    }

    companion object {
        private const val TAG = "ImageSaver"
    }
}