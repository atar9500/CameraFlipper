package com.atar.cameraflipper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import kotlinx.android.synthetic.main.fragment_camera.*
import java.lang.ref.WeakReference
import android.view.animation.LinearInterpolator
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.R.attr.name



class CameraFragment : Fragment() {

    /**
     * Data
     */
    private lateinit var mSensorManager: SensorManager
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)
    private val mSensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Toast.makeText(context!!, "$accuracy", Toast.LENGTH_SHORT).show()
        }

        override fun onSensorChanged(event: SensorEvent) {
            var rotateCamera = false
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(
                    event.values,
                    0,
                    mAccelerometerReading,
                    0,
                    mAccelerometerReading.size
                )
                rotateCamera = true
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(
                    event.values,
                    0,
                    mMagnetometerReading,
                    0,
                    mMagnetometerReading.size
                )
                rotateCamera = true
            }
            if (rotateCamera) {
                AngleCalculationTask(frca_cam).executeOnExecutor(
                    AsyncTask.SERIAL_EXECUTOR,
                    mAccelerometerReading,
                    mMagnetometerReading
                )
            }
        }

    }

    /**
     * Inner Classes
     */
    private class AngleCalculationTask internal constructor(viewToFlip: View) :
        AsyncTask<FloatArray, Void, Float>() {

        private val mFlippedViewRef: WeakReference<View> = WeakReference(viewToFlip)

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
            val flippedView = mFlippedViewRef.get()
            if (flippedView != null) {
                flippedView.rotation = result
            }
        }
    }

    /**
     * Fragment Methods
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onResume() {
        super.onResume()
        context?.let {
            mSensorManager = it.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?.also { accelerometer ->
                    mSensorManager.registerListener(
                        mSensorEventListener,
                        accelerometer,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        SensorManager.SENSOR_DELAY_UI
                    )
                }
            mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
                mSensorManager.registerListener(
                    mSensorEventListener,
                    magneticField,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mSensorManager.unregisterListener(mSensorEventListener)
    }

}
