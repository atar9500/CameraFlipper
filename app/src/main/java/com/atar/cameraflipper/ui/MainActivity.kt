package com.atar.cameraflipper.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.Observer
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.atar.cameraflipper.R
import com.atar.cameraflipper.utils.SingleEvent
import androidx.lifecycle.ViewModelProviders

class MainActivity : AppCompatActivity() {

    /**
     * Data
     */
    private lateinit var mNavController: NavController
    private lateinit var mViewModel: HomeViewModel
    private val mPermissionsGrantedObserver = Observer<SingleEvent<Boolean>> {event ->
        event.getContentIfNotHandled()?.let {
            if (it) {
                mNavController.navigate(R.id.action_permissionsFragment_to_cameraFragment)
            } else {
                mNavController.navigate(R.id.action_cameraFragment_to_permissionsFragment)
            }
        }
    }

    /**
     * AppCompatActivity Methods
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mNavController = findNavController(R.id.nav_host_fragment)

        mViewModel = ViewModelProviders.of(this).get(HomeViewModel::class.java)
        mViewModel.arePermissionsGranted().observe(this, mPermissionsGrantedObserver)
    }

    override fun onDestroy() {
        mViewModel.arePermissionsGranted().removeObserver(mPermissionsGrantedObserver)
        super.onDestroy()
    }
}
