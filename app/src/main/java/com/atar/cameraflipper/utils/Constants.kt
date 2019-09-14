package com.atar.cameraflipper.utils

import android.Manifest
import android.os.Environment
import android.util.SparseIntArray
import android.view.Surface
import com.atar.cameraflipper.BuildConfig
import java.io.File

object Constants {
    val APP_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    const val STATE_PREVIEW = 0
    const val STATE_WAITING_LOCK = 1
    const val STATE_WAITING_PRECAPTURE = 2
    const val STATE_WAITING_NON_PRECAPTURE = 3
    const val STATE_PICTURE_TAKEN = 4

    const val FILE_PROVIDER_AUTH =  "${BuildConfig.APPLICATION_ID}.provider"
    const val PIC_FILE_NAME = "camera_flipper_demo_pic.jpg"
}