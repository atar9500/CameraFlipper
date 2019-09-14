package com.atar.cameraflipper.ui

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProviders
import com.atar.cameraflipper.utils.Constants
import com.atar.cameraflipper.R
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.fragment_permissions.*

class PermissionsFragment : Fragment() {

    /**
     * Data
     */
    private lateinit var mViewModel: HomeViewModel
    private val mPermissionsListener = object : MultiplePermissionsListener {
        override fun onPermissionsChecked(report: MultiplePermissionsReport) {
            when {
                report.isAnyPermissionPermanentlyDenied -> {
                    AlertDialog.Builder(context!!)
                        .setCancelable(false)
                        .setTitle(R.string.permission_request)
                        .setMessage(R.string.permission_request_content_denied)
                        .setPositiveButton(R.string.setting) { _, _ ->
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts("package", activity!!.packageName, null)
                            intent.data = uri
                            startActivity(intent)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                report.areAllPermissionsGranted() -> {
                    mViewModel.setPermissionsGranted(true)
                }
            }
        }

        override fun onPermissionRationaleShouldBeShown(
            permissions: MutableList<PermissionRequest>?,
            token: PermissionToken?
        ) {
            AlertDialog.Builder(context!!)
                .setCancelable(false)
                .setTitle(R.string.permission_request)
                .setMessage(R.string.permission_request_content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    token?.continuePermissionRequest()
                }.setNegativeButton(android.R.string.cancel) { _, _ ->
                    token?.cancelPermissionRequest()
                }.show()
        }

    }

    /**
     * Fragment Functions
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        mViewModel = ViewModelProviders.of(activity!!).get(HomeViewModel::class.java)
        frap_allow.setOnClickListener {
            checkCameraPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        checkCameraPermissions()
    }

    /**
     * PermissionsFragment Functions
     */
    private fun checkCameraPermissions() {
        val permissions = mutableListOf<String>()
        Constants.APP_PERMISSIONS.forEach {
            val permissionStatus = ContextCompat.checkSelfPermission(activity!!, it)
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                permissions.add(it)
            }
        }
        if (permissions.isEmpty()) {
            mViewModel.setPermissionsGranted(true)
        } else {
            Dexter.withActivity(activity)
                .withPermissions(permissions).withListener(mPermissionsListener).check()
        }
    }

}
