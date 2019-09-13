package com.atar.cameraflipper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import androidx.fragment.app.Fragment
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.fragment_camera.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment() {

    companion object {
        private const val TAG = "CameraFragment"
        private val mAppPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080

        private const val STATE_PREVIEW = 0
        private const val STATE_WAITING_LOCK = 1
        private const val STATE_WAITING_PRECAPTURE = 2
        private const val STATE_WAITING_NON_PRECAPTURE = 3
        private const val STATE_PICTURE_TAKEN = 4

        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        @JvmStatic
        private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()

            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                    option.height == option.width * h / w
                ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return when {
                bigEnough.size > 0 -> {
                    Collections.min(bigEnough, CompareSizesByArea())
                }
                notBigEnough.size > 0 -> {
                    Collections.max(notBigEnough, CompareSizesByArea())
                }
                else -> {
                    // Log.e(TAG, "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }
    }

    /**
     * Motion sensors related data
     */
    private lateinit var mSensorManager: SensorManager
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)
    private val mCameraOpenCloseLock = Semaphore(1)
    private var mSensorOrientation: Int? = 0
    private var mFlashSupported = false
    private var mRequestingPermissions = false
    private var mCameraDevice: CameraDevice? = null
    private var mState = STATE_PREVIEW
    private var mBackgroundThread: HandlerThread? = null
    private var mIsSelfie = false
    private val mSensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
     * Camera related data
     */
    private var mBackgroundHandler: Handler? = null
    private lateinit var mPreviewSize: Size
    private var mImageReader: ImageReader? = null
    private lateinit var mCameraId: String
    private var mCaptureSession: CameraCaptureSession? = null
    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder
    private lateinit var mPreviewRequest: CaptureRequest
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        mBackgroundHandler?.post(ImageSaver(it.acquireNextImage()))
    }
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    }
    private val mPermissionsListener = object : MultiplePermissionsListener {
        override fun onPermissionsChecked(report: MultiplePermissionsReport) {
            when {
                report.isAnyPermissionPermanentlyDenied -> {
                    AlertDialog.Builder(context!!)
                        .setTitle(R.string.permission_request)
                        .setMessage(R.string.permission_request_content_denied)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts("package", activity!!.packageName, null)
                            intent.data = uri
                            startActivity(intent)
                        }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
                }
                else -> {
                    openCamera()
                }
            }
        }

        override fun onPermissionRationaleShouldBeShown(
            permissions: MutableList<PermissionRequest>?,
            token: PermissionToken?
        ) {
            AlertDialog.Builder(context!!)
                .setTitle(R.string.permission_request)
                .setMessage(R.string.permission_request_content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    token?.continuePermissionRequest()
                }.setNegativeButton(android.R.string.cancel) { _, _ ->
                    token?.cancelPermissionRequest()
                }.show()
        }

    }
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        mState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    mState = STATE_PICTURE_TAKEN
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: CaptureResult
        ) {
            process(result)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
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
        mSensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
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

        startBackgroundThread()
        frca_cam.visibility = View.VISIBLE

        frca_capture.setOnClickListener {
            lockFocus()
        }

        frca_switch.setOnClickListener {
            mIsSelfie = !mIsSelfie
            closeCamera()
            openCamera()
        }

        if (!mRequestingPermissions) {
            reopenCamera()
        }
        mRequestingPermissions = false
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        frca_cam.visibility = View.GONE
        mSensorManager.unregisterListener(mSensorEventListener)
        super.onPause()
    }

    /**
     * CameraFragment Functions
     */
    private fun reopenCamera() {
        if (frca_cam.isAvailable) {
            openCamera()
        } else {
            frca_cam.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!checkCameraPermissions()) {
            return
        }
        setUpCameraOutputs(frca_cam.width, frca_cam.height)
        configureTransform(frca_cam.width, frca_cam.height)
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Checking if all our permissions are granted,
     * returns true if so, requests the non-granted permissions
     * and return false otherwise
     */
    private fun checkCameraPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        mAppPermissions.forEach {
            val permissionStatus = ContextCompat.checkSelfPermission(activity!!, it)
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                permissions.add(it)
            }
        }
        if (permissions.isEmpty()) {
            return true
        }
        mRequestingPermissions = true
        Dexter.withActivity(activity)
            .withPermissions(permissions).withListener(mPermissionsListener).check()
        return false
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection == null) {
                    continue
                } else if (!mIsSelfie
                    && cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                } else if (mIsSelfie
                    && cameraDirection != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                    listOf(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea()
                )
                mImageReader = ImageReader.newInstance(
                    largest.width, largest.height, ImageFormat.JPEG, 2
                ).apply {
                    setOnImageAvailableListener(
                        onImageAvailableListener,
                        mBackgroundHandler
                    )
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val defaultDisplay = activity?.windowManager?.defaultDisplay
                val displayRotation = defaultDisplay?.rotation

                mSensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val swappedDimensions = areDimensionsSwapped(displayRotation!!)

                val displaySize = Point()
                defaultDisplay.getSize(displaySize)
                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                var maxPreviewWidth =
                    if (swappedDimensions) displaySize.y else displaySize.x
                var maxPreviewHeight =
                    if (swappedDimensions) displaySize.x else displaySize.y

                if (maxPreviewWidth > MAX_PREVIEW_HEIGHT) maxPreviewWidth =
                    MAX_PREVIEW_HEIGHT
                if (maxPreviewHeight > MAX_PREVIEW_WIDTH) maxPreviewHeight =
                    MAX_PREVIEW_WIDTH

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight,
                    largest
                )

                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    frca_cam.setAspectRatio(mPreviewSize.width, mPreviewSize.height)
                } else {
                    frca_cam.setAspectRatio(mPreviewSize.height, mPreviewSize.width)
                }

                // Check if the flash is supported.
                mFlashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                mCameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            // Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            // ErrorDialog.newInstance(getString(R.string.camera_error))
            //    .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = activity?.windowManager?.defaultDisplay?.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, mPreviewSize.height.toFloat(), mPreviewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale =
                (viewHeight.toFloat() / mPreviewSize.height).coerceAtLeast(viewWidth.toFloat() / mPreviewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        frca_cam.setTransform(matrix)
    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param displayRotation The current rotation of the display
     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (mFlashSupported) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
        }
    }

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its mState.
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            this@CameraFragment.mCameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@CameraFragment.activity?.finish()
        }

    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = frca_cam.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            mPreviewRequestBuilder.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice?.createCaptureSession(
                listOf(surface, mImageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (mCameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        mCaptureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            mPreviewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            setAutoFlash(mPreviewRequestBuilder)

                            // Finally, we start displaying the camera preview.
                            mPreviewRequest = mPreviewRequestBuilder.build()
                            mCaptureSession?.setRepeatingRequest(
                                mPreviewRequest,
                                mCaptureCallback, mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.i(TAG, "FAILED!")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.mCaptureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        try {
            if (activity == null || mCameraDevice == null) return
            val rotation = activity!!.windowManager.defaultDisplay.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = mCameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )?.apply {
                addTarget(mImageReader!!.surface)

                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                // We have to take that into account and rotate JPEG properly.
                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                set(
                    CaptureRequest.JPEG_ORIENTATION,
                    (ORIENTATIONS.get(rotation) + mSensorOrientation!! + 270) % 360
                )

                // Use the same AE and AF modes as the preview.
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }?.also { setAutoFlash(it) }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Toast.makeText(context!!, R.string.image_saved, Toast.LENGTH_LONG).show()
                    unlockFocus()
                }
            }

            mCaptureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder!!.build(), captureCallback, null)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            setAutoFlash(mPreviewRequestBuilder)
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(), mCaptureCallback,
                mBackgroundHandler
            )
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW
            mCaptureSession?.setRepeatingRequest(
                mPreviewRequest, mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.mCaptureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(), mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            mCaptureSession?.close()
            mCaptureSession = null
            mCameraDevice?.close()
            mCameraDevice = null
            mImageReader?.close()
            mImageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground").also { it.start() }
        mBackgroundHandler = Handler(mBackgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            // Tell #captureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(), mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

}

