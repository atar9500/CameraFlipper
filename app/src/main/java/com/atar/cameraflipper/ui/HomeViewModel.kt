package com.atar.cameraflipper.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atar.cameraflipper.utils.SingleEvent
import kotlinx.coroutines.launch

class HomeViewModel: ViewModel() {

    private val mPermissionsGranted = MutableLiveData<SingleEvent<Boolean>>()

    fun arePermissionsGranted(): LiveData<SingleEvent<Boolean>> {
        return mPermissionsGranted
    }

    fun setPermissionsGranted(isGranted: Boolean) = viewModelScope.launch {
        mPermissionsGranted.value = SingleEvent(isGranted)
    }

}