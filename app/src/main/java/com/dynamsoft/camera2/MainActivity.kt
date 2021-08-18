package com.dynamsoft.camera2

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback
import android.support.v4.content.ContextCompat
import android.support.v4.view.GestureDetectorCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class DualCamera(val logicalId: String, val physicalId1: String, val physicalId2: String)
typealias DualCameraOutputs =  Triple<MutableList<Surface?>?, MutableList<Surface?>, MutableList<Surface?>>

class MainActivity : AppCompatActivity(), OnRequestPermissionsResultCallback, GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener {
    private var surface1: Surface? = null
    private var surface2: Surface? = null
    private lateinit var sv1: SurfaceView
    private lateinit var sv2: SurfaceView
    private lateinit var mDetector: GestureDetectorCompat
    private val DEBUG_TAG = "Gesture"
    private var isOnTop = true

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private val ORIENTATIONS = SparseIntArray()
        private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int,
                                      textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
            val bigEnough: MutableList<Size> = ArrayList()
            val notBigEnough: MutableList<Size> = ArrayList()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth &&
                            option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }
            return if (bigEnough.size > 0) {
                Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.size > 0) {
                Collections.max(notBigEnough, CompareSizesByArea())
            } else {
                choices[0]
            }
        }

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    private val mCameraOpenCloseLock = Semaphore(1)
    private var mCameraId: String? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mPreviewRequest: CaptureRequest? = null
    private var mSensorOrientation = 0
    private var mPreviewSize: Size? = null
    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            if (null != mCaptureSession) {
                mCaptureSession!!.close()
                mCaptureSession = null
            }
            if (null != mCameraDevice) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    fun openMultiCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val dualCamera = findShortLongCameraPair(manager)!!
        val outputTargets = DualCameraOutputs(
                null, mutableListOf(surface1), mutableListOf(surface2))

// Here you open the logical camera, configure the outputs and create a session
        createDualCameraSession(manager, dualCamera, targets = outputTargets) { session ->

            // Create a single request which has one target for each physical camera
            // NOTE: Each target receive frames from only its associated physical camera
            val requestTemplate = CameraDevice.TEMPLATE_PREVIEW
            val captureRequest = session.device.createCaptureRequest(requestTemplate).apply {
                arrayOf(surface1, surface2).forEach { addTarget(it) }
            }.build()

            // Set the sticky request for the session and you are done
            session.setRepeatingRequest(captureRequest, null, null)
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    var context: Context? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        mTextureView = AutoFitTextureView(this)
//        mTextureView!!.surfaceTextureListener = this
        setContentView(R.layout.activity_main)

        mDetector = GestureDetectorCompat(this, this)
        // Set the gesture detector as the double tap
        // listener.
        mDetector.setOnDoubleTapListener(this)

        context = this

        sv1 = findViewById<SurfaceView>(R.id.sf1)
        sv2 = findViewById<SurfaceView>(R.id.sf2)
        surface1 = sv1.holder.surface
        surface2 = sv2.holder.surface

//        sv1.holder.addCallback(object: SurfaceHolder.Callback {
//            override fun surfaceChanged(holder: SurfaceHolder, format: Int,
//                                        width: Int, height: Int) {
//
//            }
//
//            override fun surfaceCreated(holder: SurfaceHolder) {
//
//                openMultiCamera()
//
//            }
//
//            override fun surfaceDestroyed(holder: SurfaceHolder) {
//
//            }
//        })

        sv2.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder, format: Int,
                                        width: Int, height: Int) {

            }

            override fun surfaceCreated(holder: SurfaceHolder) {

                openMultiCamera()

            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {

            }
        })
    }

    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height -
                    rhs.width.toLong() * rhs.height)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (mDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun onDown(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onDown: $event")
        return true
    }

    override fun onFling(
            event1: MotionEvent,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float
    ): Boolean {
        Log.d(DEBUG_TAG, "onFling: $event1 $event2")
        return true
    }

    override fun onLongPress(event: MotionEvent) {
        Log.d(DEBUG_TAG, "onLongPress: $event")
    }

    override fun onScroll(
            event1: MotionEvent,
            event2: MotionEvent,
            distanceX: Float,
            distanceY: Float
    ): Boolean {
        Log.d(DEBUG_TAG, "onScroll: $event1 $event2")
        return true
    }

    override fun onShowPress(event: MotionEvent) {
        Log.d(DEBUG_TAG, "onShowPress: $event")
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onSingleTapUp: $event")
        return true
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        toggleView()
        return true
    }

    fun toggleView() {
        if (isOnTop) {
            isOnTop = false
            sv1.setZOrderMediaOverlay(true)
//            sv2.setZOrderMediaOverlay(false)
            sv1.setZOrderOnTop(true)
//            sv2.setZOrderOnTop(false)
//            sv1.visibility = View.VISIBLE
//            sv2.visibility = View.INVISIBLE
        }
        else {
            isOnTop = true
            sv2.setZOrderMediaOverlay(true)
//            sv1.setZOrderMediaOverlay(false)
            sv2.setZOrderOnTop(true)
//            sv1.setZOrderOnTop(false)
//            sv1.visibility = View.INVISIBLE
//            sv2.visibility = View.VISIBLE
        }

    }

    override fun onDoubleTapEvent(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onDoubleTapEvent: $event")
        return true
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        Log.d(DEBUG_TAG, "onSingleTapConfirmed: $event")
        return true
    }

    fun findDualCameras(manager: CameraManager, facing: Int? = null): List<DualCamera> {
        val dualCameras = ArrayList<DualCamera>()

        // Iterate over all the available camera characteristics
        manager.cameraIdList.map {
            Pair(manager.getCameraCharacteristics(it), it)
        }.filter {
            // Filter by cameras facing the requested direction
            facing == null || it.first.get(CameraCharacteristics.LENS_FACING) == facing
        }.filter {
            // Filter by logical cameras
            it.first.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.forEach {
            // All possible pairs from the list of physical cameras are valid results
            // NOTE: There could be N physical cameras as part of a logical camera grouping
            val physicalCameras = it.first.physicalCameraIds.toTypedArray()
            for (idx1 in 0 until physicalCameras.size) {
                for (idx2 in (idx1 + 2) until physicalCameras.size) {
                    dualCameras.add(DualCamera(
                            it.second, physicalCameras[idx1], physicalCameras[idx2]))
                }
            }
        }

        return dualCameras
    }



    fun createDualCameraSession(cameraManager: CameraManager,
                                dualCamera: DualCamera,
                                targets: DualCameraOutputs,
                                executor: Executor = AsyncTask.SERIAL_EXECUTOR,
                                callback: (CameraCaptureSession) -> Unit) {

        // Create 3 sets of output configurations: one for the logical camera, and
        // one for each of the physical cameras.
        val outputConfigsLogical = targets.first?.map { OutputConfiguration(it) }
        val outputConfigsPhysical1 = targets.second?.map {
            OutputConfiguration(it).apply { setPhysicalCameraId(dualCamera.physicalId1) } }
        val outputConfigsPhysical2 = targets.third?.map {
            OutputConfiguration(it).apply { setPhysicalCameraId(dualCamera.physicalId2) } }

        // Put all the output configurations into a single flat array
        val outputConfigsAll = arrayOf(
                outputConfigsLogical, outputConfigsPhysical1, outputConfigsPhysical2)
                .filterNotNull().flatMap { it }

        // Instantiate a session configuration that can be used to create a session
        val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                outputConfigsAll, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = callback(session)
            // Omitting for brevity...
            override fun onConfigureFailed(session: CameraCaptureSession) = session.device.close()
        })

        // Open the logical camera using the previously defined function
        openDualCamera(cameraManager, dualCamera, executor = executor) {

            // Finally create the session and return via callback
            it.createCaptureSession(sessionConfiguration)
        }
    }

    fun findShortLongCameraPair(manager: CameraManager, facing: Int? = null): DualCamera? {

        return findDualCameras(manager, facing).map {
            val characteristics1 = manager.getCameraCharacteristics(it.physicalId1)
            val characteristics2 = manager.getCameraCharacteristics(it.physicalId2)

            // Query the focal lengths advertised by each physical camera
            val focalLengths1 = characteristics1.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)
            val focalLengths2 = characteristics2.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)

            // Compute the largest difference between min and max focal lengths between cameras
            val focalLengthsDiff1 = focalLengths2.max()!! - focalLengths1.min()!!
            val focalLengthsDiff2 = focalLengths1.max()!! - focalLengths2.min()!!

            // Return the pair of camera IDs and the difference between min and max focal lengths
            if (focalLengthsDiff1 < focalLengthsDiff2) {
                Pair(DualCamera(it.logicalId, it.physicalId1, it.physicalId2), focalLengthsDiff1)
            } else {
                Pair(DualCamera(it.logicalId, it.physicalId2, it.physicalId1), focalLengthsDiff2)
            }

            // Return only the pair with the largest difference, or null if no pairs are found
        }.maxBy { it.second }?.first
    }

    fun openDualCamera(cameraManager: CameraManager,
                       dualCamera: DualCamera,
                       executor: Executor = AsyncTask.SERIAL_EXECUTOR,
                       callback: (CameraDevice) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        cameraManager.openCamera(dualCamera.logicalId, executor, object: CameraDevice.StateCallback(){
            override fun onOpened(device: CameraDevice) {
                callback(device)
                mCameraOpenCloseLock.release()
                mCameraDevice = device
            }

            override fun onDisconnected(device: CameraDevice) {
                onDisconnected(device)
                mCameraOpenCloseLock.release()
                device.close()
                mCameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                mCameraOpenCloseLock.release()
                device.close()
                mCameraDevice = null
                val activity: Activity = this@MainActivity
                activity?.finish()
            }
        })
    }

    private fun requestCameraPermission() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.size > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                }
                return
            }
        }
    }
}