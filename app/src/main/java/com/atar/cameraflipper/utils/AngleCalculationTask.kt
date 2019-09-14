package com.atar.cameraflipper.utils

import android.hardware.SensorManager
import android.os.AsyncTask
import java.lang.ref.WeakReference

class AngleCalculationTask(calculationListener: CalculationListener) :
    AsyncTask<FloatArray, Void, Float>() {

    private val mCalculationListener: WeakReference<CalculationListener> = WeakReference(calculationListener)

    override fun doInBackground(vararg params: FloatArray?): Float {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            params[0],
            params[1]
        )
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        return -(orientationAngles[2] * (180 / Math.PI)).toFloat()
    }

    override fun onPostExecute(result: Float) {
        mCalculationListener.get()?.onCalculationEnd(result)
    }

    interface CalculationListener {
        fun onCalculationEnd(result: Float)
    }

}