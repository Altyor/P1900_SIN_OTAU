package com.siliconlabs.bledemo.features.firmware_browser.domain

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object ScanFilterDefaults {
    private val _current: MutableLiveData<ScanFilterConfig> =
        MutableLiveData(ScanFilterConfig.FACTORY)

    val current: LiveData<ScanFilterConfig> get() = _current

    fun get(): ScanFilterConfig = _current.value ?: ScanFilterConfig.FACTORY

    fun set(config: ScanFilterConfig) {
        _current.postValue(config)
    }

    fun resetToFactory() {
        _current.postValue(ScanFilterConfig.FACTORY)
    }
}
