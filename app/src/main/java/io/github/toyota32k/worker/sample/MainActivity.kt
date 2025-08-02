package io.github.toyota32k.worker.sample

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.intBinding
import io.github.toyota32k.dialog.broker.UtPermissionBroker
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.worker.InProcForegroundWorker.Companion.inProcForegroundWorker
import io.github.toyota32k.utils.worker.InProcWorker.Companion.inProcWorker
import io.github.toyota32k.worker.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class MainActivity : UtMortalActivity() {
    class MainViewModel(application: Application): AndroidViewModel(application) {
        val durationInt = MutableStateFlow(60)
        val duration = durationInt.map { it.seconds }.stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = 60.seconds)
        val busy = MutableStateFlow(false)

        fun inProcWorkerDemo() {
            if (busy.value) return
            busy.value = true
            UtImmortalTask.launchTask("inProcWorkerDemo") {
                try {
                    val dialogViewModel = createViewModel<ProgressDialog.ProgressViewModel>()
                    immortalCoroutineScope.launch {
                        showDialog(taskName) { ProgressDialog() }
                    }

                    var cancelled = false
                    dialogViewModel.onCancel {
                        cancelled = true
                    }
                    inProcWorker(getApplication<Application>().applicationContext) {
                        fun progress(current: Long, total: Long) {
                            dialogViewModel.setProgress(current, total)
                        }
                        for (i in 1..duration.value.inWholeSeconds) {
                            delay(1000) // 1秒待機
                            progress(i, duration.value.inWholeSeconds)
                            if (cancelled) {
                                dialogViewModel.closeDialog(false)
                                break
                            }
                        }
                        dialogViewModel.complete()
                    }
                } finally {
                    busy.value = false
                }
            }
        }
        fun inProcForegroundWorkerDemo() {
            if (busy.value) return
            busy.value = true

            UtImmortalTask.launchTask("inProcWorkerDemo") {
                withActivity<MainActivity,Unit> { activity->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.permissionBroker.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                try {
                    val dialogViewModel = createViewModel<ProgressDialog.ProgressViewModel>()
                    immortalCoroutineScope.launch {
                        showDialog(taskName) { ProgressDialog() }
                    }

                    var cancelled = false
                    dialogViewModel.onCancel {
                        cancelled = true
                    }
                    inProcForegroundWorker(getApplication<Application>().applicationContext, "Foreground Worker", "Processing...", uploading=false) { sink->
                        fun progress(current: Long, total: Long) {
                            val percent = dialogViewModel.setProgress(current, total)
                            sink.notify(current==total, progressInPercent=percent)
                        }
                        for (i in 1..duration.value.inWholeSeconds) {
                            delay(1000) // 1秒待機
                            progress(i, duration.value.inWholeSeconds)
                            if (cancelled) {
                                dialogViewModel.closeDialog(false)
                                break
                            }
                        }
                        dialogViewModel.complete()
                    }
                } finally {
                    busy.value = false
                }
            }
        }

        fun utTaskWorkerDemo() {
            DemoWorker.start(application.applicationContext, false, durationInt.value.toLong())
        }
        fun utTaskWorkerInFgDemo() {
            UtImmortalTask.launchTask("inProcWorkerDemo") {
                withActivity<MainActivity, Unit> { activity ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.permissionBroker.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                DemoWorker.start(application.applicationContext, true, durationInt.value.toLong())
            }
        }
    }

    override val logger = UtLog("WorkerDemo", null, this::class.java)
    private lateinit var controls: ActivityMainBinding
    private val binder = Binder()
    private val viewModel by viewModels<MainViewModel>()
    private val permissionBroker = UtPermissionBroker(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controls = ActivityMainBinding.inflate(layoutInflater)
        setContentView(controls.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binder
            .owner(this)
            .intBinding(controls.durationInput, viewModel.durationInt)
            .bindCommand(LiteUnitCommand(viewModel::inProcWorkerDemo), controls.inProcWorkerDemo)
            .bindCommand(LiteUnitCommand(viewModel::inProcForegroundWorkerDemo), controls.inProcFgWorkerDemo)
            .bindCommand(LiteUnitCommand(viewModel::utTaskWorkerDemo), controls.utTaskWorkerDemo)
            .bindCommand(LiteUnitCommand(viewModel::utTaskWorkerInFgDemo), controls.utTaskWorkerFgDemo)
    }

//    override fun onResume() {
//        super.onResume()
//
//        lifecycleScope.launch {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                if (permissionBroker.isPermitted(Manifest.permission.POST_NOTIFICATIONS)) {
//                    logger.debug("permission already granted")
//                } else {
//                    logger.debug("requesting permission")
//                    if (permissionBroker.requestPermission(Manifest.permission.POST_NOTIFICATIONS)) {
//                        logger.debug("permission accepted")
//                    } else {
//                        logger.warn("permission denied")
//                    }
//                }
//            }
//        }
//    }
}