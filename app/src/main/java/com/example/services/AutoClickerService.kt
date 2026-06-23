package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.data.AutoClickerDatabase
import com.example.data.ClickStep
import com.example.data.ProfileEntity
import com.example.data.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutoClickerService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "AutoClickerService"
        
        @Volatile
        var instance: AutoClickerService? = null
            private set

        val serviceActive = MutableStateFlow(false)
    }

    // Lifecycle requirements for ComposeView
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ProfileRepository

    // Clicker state parameters
    private var automationJob: Job? = null
    
    private val _isAutomating = MutableStateFlow(false)
    val isAutomating = _isAutomating.asStateFlow()

    private var activeMode = "single" // "single", "multi", "swipe"
    private var clickInterval = 1000L // default 1 second
    private var clickDuration = 50L // default 50ms hold
    private var repeatCount = -1 // infinite
    private var runDurationSeconds = -1 // infinite
    private var loadedProfileId: Int? = null

    // Floating windows tracking
    private var toolbarView: ComposeView? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    // Dynamic targets
    private val targetsList = mutableStateListOf<OverlayTarget>()
    private var targetCount = 0

    // Custom overlay target class
    data class OverlayTarget(
        val id: Int,
        val label: String,
        var x: Int,
        var y: Int,
        var view: ComposeView,
        var params: WindowManager.LayoutParams
    )

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val database = AutoClickerDatabase.getDatabase(this)
        repository = ProfileRepository(database.profileDao())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        instance = this
        serviceActive.value = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "Service Unbound")
        instance = null
        serviceActive.value = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopAutomation()
        removeOverlays()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceActive.value = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        stopAutomation()
        removeOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // Method to toggle floating dashboards
    fun showOverlays(
        mode: String,
        interval: Long = 1000L,
        duration: Long = 50L,
        repeat: Int = -1,
        profileId: Int? = null,
        initialSteps: List<ClickStep> = emptyList()
    ) {
        // Clear previous runs
        stopAutomation()
        removeOverlays()

        this.activeMode = mode
        this.clickInterval = interval
        this.clickDuration = duration
        this.repeatCount = repeat
        this.loadedProfileId = profileId

        // Add Floating control bar
        createToolbar()

        // Create initial targets
        if (initialSteps.isNotEmpty()) {
            initialSteps.forEach { step ->
                if (step.type == "click") {
                    addTargetPointer(step.startX, step.startY)
                } else if (step.type == "swipe") {
                    // Supported swipe mapping is modeled as adding a pair of points (Start, End)
                    addTargetPointer(step.startX, step.startY, "S")
                    addTargetPointer(step.endX, step.endY, "E")
                }
            }
        } else {
            // Default placements if no preset coordinates are supplied
            when (mode) {
                "single" -> addTargetPointer(300, 600)
                "multi" -> {
                    addTargetPointer(250, 500)
                    addTargetPointer(450, 700)
                }
                "swipe" -> {
                    addTargetPointer(300, 400, "S")
                    addTargetPointer(300, 800, "E")
                }
            }
        }
    }

    fun removeOverlays() {
        // Remove toolbar
        toolbarView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove toolbar overlay: ${e.message}")
            }
        }
        toolbarView = null
        toolbarParams = null

        // Remove target points
        targetsList.forEach { target ->
            try {
                windowManager.removeView(target.view)
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "Failed to remove target point overlay: ${e.message}")
            }
        }
        targetsList.clear()
        targetCount = 0
    }

    private fun createToolbar() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Centered on top of screen
            x = (screenWidth - (280 * density).toInt()) / 2
            y = (120 * density).toInt()
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AutoClickerService)
            setViewTreeSavedStateRegistryOwner(this@AutoClickerService)
            setViewTreeViewModelStoreOwner(this@AutoClickerService)
            
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF6750A4), // Minimalist M3 Primary
                        background = Color(0xFF1E1E22),
                        surface = Color(0xFF151518)
                    )
                ) {
                    FloatingToolbarUI()
                }
            }
        }

        toolbarView = composeView
        toolbarParams = params
        windowManager.addView(composeView, params)
    }

    @Composable
    fun FloatingToolbarUI() {
        val automating by isAutomating.collectAsState()
        var showSettingsOverlay by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .wrapContentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE0121214))
                .border(1.dp, Color(0x336750A4), RoundedCornerShape(20.dp))
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag Handle for moving entire panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            toolbarParams?.let { params ->
                                params.x += dragAmount.x.toInt()
                                params.y += dragAmount.y.toInt()
                                windowManager.updateViewLayout(toolbarView, params)
                            }
                        }
                    }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4B5563))
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                // Play/Pause Action
                IconButton(
                    onClick = {
                        if (automating) stopAutomation() else startAutomation()
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (automating) Color(0xFFBA1A1A) else Color(0xFF6750A4)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    if (automating) {
                        Text(
                            text = "||",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Trigger Automation",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Add Target Point (+), only applicable to Multi & Swipe modes
                if (activeMode == "multi" || activeMode == "swipe") {
                    IconButton(
                        onClick = {
                            if (activeMode == "swipe") {
                                addTargetPointer(100 + (targetsList.size * 30), 400, "S")
                                addTargetPointer(100 + (targetsList.size * 30) + 100, 600, "E")
                            } else {
                                addTargetPointer(200 + (targetsList.size * 50), 600)
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF2D2D30)
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Location Node",
                            tint = Color(0xFF6750A4),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Remove Target Point (-)
                    IconButton(
                        onClick = {
                            if (targetsList.isNotEmpty()) {
                                if (activeMode == "swipe") {
                                    // Swipe deletes a pair
                                    if (targetsList.size >= 2) {
                                        removeTarget(targetsList.last())
                                        removeTarget(targetsList.last())
                                    }
                                } else {
                                    removeTarget(targetsList.last())
                                }
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF2D2D30)
                        ),
                        modifier = Modifier.size(38.dp),
                        enabled = targetsList.isNotEmpty()
                    ) {
                        Text(
                            text = "—",
                            color = if (targetsList.isNotEmpty()) Color(0xFFBA1A1A) else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Quick Settings configuration within panel
                IconButton(
                    onClick = { showSettingsOverlay = !showSettingsOverlay },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF2D2D30)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Panel Quick Customizer",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Close overlays
                IconButton(
                    onClick = {
                        stopAutomation()
                        removeOverlays()
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF2D2D30)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Panel",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Quick customization dropdown
            AnimatedVisibility(visible = showSettingsOverlay) {
                QuickSettingsPane()
            }
        }
    }

    @Composable
    fun QuickSettingsPane() {
        var intervalVal by remember { mutableStateOf(clickInterval.toString()) }
        var repeatVal by remember { mutableStateOf(repeatCount.toString()) }

        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(200.dp)
                .background(Color(0xFF151518), RoundedCornerShape(12.dp))
                .border(0.5.dp, Color(0x226750A4), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Quick Configurations",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6750A4)
            )

            // Interval MS input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delay (ms)", fontSize = 10.sp, color = Color.White)
                OutlinedTextField(
                    value = intervalVal,
                    onValueChange = {
                        intervalVal = it
                        it.toLongOrNull()?.let { num ->
                            clickInterval = num.coerceAtLeast(10L)
                        }
                    },
                    modifier = Modifier.size(width = 80.dp, height = 30.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                )
            }

            // Cycles input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Loops (-1=∞)", fontSize = 10.sp, color = Color.White)
                OutlinedTextField(
                    value = repeatVal,
                    onValueChange = {
                        repeatVal = it
                        it.toIntOrNull()?.let { num ->
                            repeatCount = num
                        }
                    },
                    modifier = Modifier.size(width = 80.dp, height = 30.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                )
            }

            // Save layout/coords directly to selected profile
            Button(
                onClick = {
                    saveProfileProgress()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save Coordinates", fontSize = 10.sp, color = Color.White)
            }
        }
    }

    private fun addTargetPointer(initialX: Int, initialY: Int, specLabel: String? = null) {
        val density = resources.displayMetrics.density
        // Target dimensions matching Accessibility target standards
        val targetSize = (48 * density).toInt()

        val params = WindowManager.LayoutParams(
            targetSize,
            targetSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        targetCount++
        val label = specLabel ?: targetCount.toString()

        val targetObjId = System.identityHashCode(params) // unique identifier

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AutoClickerService)
            setViewTreeSavedStateRegistryOwner(this@AutoClickerService)
            setViewTreeViewModelStoreOwner(this@AutoClickerService)
            
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(targetObjId) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                params.x += dragAmount.x.toInt()
                                params.y += dragAmount.y.toInt()
                                windowManager.updateViewLayout(this@apply, params)
                                // Keep internal list updated with locations
                                targetsList.find { it.id == targetObjId }?.let { item ->
                                    item.x = params.x
                                    item.y = params.y
                                }
                            }
                        }
                        .background(Color(0x336750A4), CircleShape)
                        .border(1.5.dp, Color(0xFF6750A4), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Small floating ring core
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(0xFF6750A4), CircleShape)
                    )
                    // Text designation (e.g. Target Order Number)
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .background(Color(0xE91E1E22), CircleShape)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        val overlayTargetObj = OverlayTarget(
            id = targetObjId,
            label = label,
            x = params.x,
            y = params.y,
            view = composeView,
            params = params
        )

        targetsList.add(overlayTargetObj)
        windowManager.addView(composeView, params)
    }

    private fun removeTarget(target: OverlayTarget) {
        try {
            windowManager.removeView(target.view)
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing target overlay view: ${e.message}")
        }
        targetsList.remove(target)
        targetCount = targetsList.filter { it.label.toIntOrNull() != null }.size
    }

    // Executes local save logic for current overlay layout back to database
    private fun saveProfileProgress() {
        val currentProfileId = loadedProfileId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val entity = repository.getProfileById(currentProfileId)
            if (entity != null) {
                // Collect target points
                val steps = mutableListOf<ClickStep>()
                when (activeMode) {
                    "single" -> {
                        targetsList.firstOrNull()?.let { node ->
                            steps.add(ClickStep("click", node.x, node.y, 0, 0, clickInterval, clickDuration))
                        }
                    }
                    "multi" -> {
                        targetsList.forEach { node ->
                            steps.add(ClickStep("click", node.x, node.y, 0, 0, clickInterval, clickDuration))
                        }
                    }
                    "swipe" -> {
                        // Swipe matches Start and End target elements sequently
                        var pStart: OverlayTarget? = null
                        targetsList.forEach { node ->
                            if (node.label == "S") {
                                pStart = node
                            } else if (node.label == "E" && pStart != null) {
                                steps.add(ClickStep("swipe", pStart!!.x, pStart!!.y, node.x, node.y, clickInterval, 300L))
                                pStart = null // reset pair
                            }
                        }
                    }
                }

                val serialized = ProfileEntity.buildStepsString(steps)
                val updated = entity.copy(
                    intervalMs = clickInterval,
                    repeatCount = repeatCount,
                    stepsString = serialized
                )
                repository.insert(updated)
                Log.d(TAG, "Profile saved updated layout!")
            }
        }
    }

    // Automation loops
    fun startAutomation() {
        if (_isAutomating.value) return
        _isAutomating.value = true

        automationJob = lifecycleScope.launch(Dispatchers.Main) {
            var cycleCount = 0
            while (_isAutomating.value) {
                // Check repetition limitation
                if (repeatCount > 0 && cycleCount >= repeatCount) {
                    stopAutomation()
                    break
                }

                when (activeMode) {
                    "single" -> {
                        val node = targetsList.firstOrNull()
                        if (node == null) {
                            stopAutomation()
                            break
                        }
                        // Center offsets of 48dp (approx raw calculation)
                        val density = resources.displayMetrics.density
                        val radial = (48 * density).toInt() / 2
                        
                        clickAt(node.x + radial, node.y + radial, clickDuration)
                        delay(clickInterval)
                    }
                    "multi" -> {
                        if (targetsList.isEmpty()) {
                            stopAutomation()
                            break
                        }
                        val density = resources.displayMetrics.density
                        val radial = (48 * density).toInt() / 2

                        // Sequenced execution
                        for (node in targetsList) {
                            if (!_isAutomating.value) break
                            clickAt(node.x + radial, node.y + radial, clickDuration)
                            delay(clickInterval)
                        }
                    }
                    "swipe" -> {
                        // Loops through Start -> End combinations
                        var pStart: OverlayTarget? = null
                        val density = resources.displayMetrics.density
                        val radial = (48 * density).toInt() / 2
                        var executedSwipe = false

                        for (node in targetsList) {
                            if (!_isAutomating.value) break
                            if (node.label == "S") {
                                pStart = node
                            } else if (node.label == "E" && pStart != null) {
                                swipe(
                                    pStart!!.x + radial, pStart!!.y + radial, 
                                    node.x + radial, node.y + radial, 
                                    300L
                                )
                                pStart = null
                                executedSwipe = true
                                delay(clickInterval)
                            }
                        }
                        if (!executedSwipe) {
                            stopAutomation()
                            break
                        }
                    }
                }
                cycleCount++
            }
        }
    }

    fun stopAutomation() {
        _isAutomating.value = false
        automationJob?.cancel()
        automationJob = null
    }

    // Low-level gesture generation framework hooks
    private fun clickAt(x: Int, y: Int, duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            try {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Completed Tap Gestures at ($x, $y)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.d(TAG, "Cancelled Tap Gestures at ($x, $y)")
                    }
                }, null)
            } catch (e: Exception) {
                Log.e(TAG, "Gesture Dispatch Execution error: ${e.message}")
            }
        }
    }

    private fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            try {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Completed Glide Sweep from ($startX, $startY) -> ($endX, $endY)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.d(TAG, "Cancelled Swipe Gesture")
                    }
                }, null)
            } catch (e: Exception) {
                Log.e(TAG, "Swipe Gesture execution error: ${e.message}")
            }
        }
    }
}
