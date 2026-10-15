package com.ljyh.mei.ui.screen.main.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HomePageOwner
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.repository.ColorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val colorRepository: ColorRepository,
    private val meloXRepository: MeloXRepository,
) : ViewModel() {
    val context= AppContext.instance

    private val _homePageResourceShow =
        MutableStateFlow<Resource<List<HomePageResourceShow.Data.Block>>>(Resource.Loading)
    val homePageResourceShow: StateFlow<Resource<List<HomePageResourceShow.Data.Block>>> = _homePageResourceShow
    private var observedOwner: HomePageOwner? = null
    private var displayedOwner: HomePageOwner? = null
    private var requestJob: Job? = null
    private var requestVersion = 0L

    init {
        viewModelScope.launch {
            repository.owners.collect { owner ->
                observedOwner = owner
                homePageResourceShow()
            }
        }
    }

    fun homePageResourceShow(refresh: Boolean = false) {
        val owner = observedOwner ?: return
        if (!refresh && displayedOwner == owner && _homePageResourceShow.value is Resource.Success) return
        requestJob?.cancel()
        val version = ++requestVersion
        displayedOwner = null
        _homePageResourceShow.value = Resource.Loading
        requestJob = viewModelScope.launch {
            val result = repository.getHomePageResourceShow(refresh, owner)
            val currentOwner = repository.owners.first()
            currentCoroutineContext().ensureActive()
            if (version == requestVersion && observedOwner == owner && currentOwner == owner) {
                displayedOwner = owner
                _homePageResourceShow.value = result
            }
        }
    }
    fun getCachedColor(url: String) = colorRepository.getFromMemory(url)

    suspend fun podcastProgram(id: Long) = meloXRepository.podcastProgram(id)

    suspend fun getOrExtractColor(url: String) = colorRepository.getColorOrExtract(context, url)
}
