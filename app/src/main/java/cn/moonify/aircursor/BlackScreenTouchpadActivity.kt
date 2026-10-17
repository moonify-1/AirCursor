package cn.moonify.aircursor

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.moonify.aircursor.bluetooth.AirCursorConnectionManager
import cn.moonify.aircursor.bluetooth.HidMouseManager
import cn.moonify.aircursor.bluetooth.RfcommConstants
import cn.moonify.aircursor.databinding.ActivityBlackScreenBinding
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

/**
 * 纯黑全屏极简触控板界面 (Black Screen Fullscreen Touchpad)。
 *
 * 核心特性：
 * 1. 极致省电与护眼：整屏纯黑 (#000000)，在 OLED/AMOLED 屏幕上所有像素完全熄灭不发光，
 *    同时将 Window 亮度调至最低微光极限，防刺眼且极度省电。
 * 2. 100% 物理全屏触控：隐藏系统状态栏与导航栏，延伸至屏幕刘海与边缘，整块手机屏幕均为触控板范围。
 * 3. 完整功能支持：单指微移、弹道加速、单击左键、双击拖选、双指右键、双指平滑/惯性滚动、三指多任务全手势。
 * 4. 防休眠与防误触截屏：保持屏幕常亮 (FLAG_KEEP_SCREEN_ON) 与底层防截屏安全保护 (FLAG_SECURE)。
 * 5. 便捷安全退出：按物理返回键、手机音量键或屏幕四指轻点即可立即退出。
 */
class BlackScreenTouchpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlackScreenBinding
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionManager = AirCursorConnectionManager.instance
    private val hidMouseManager get() = connectionManager.hidMouseManager
    private val rfcommClientManager get() = connectionManager.rfcommClientManager

    @Volatile
    private var currentButtonState: Byte = 0x00

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 延伸至刘海与打孔屏边缘 (Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 2. 沉浸式全屏配置：隐藏状态栏与导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 3. 屏幕保持常亮，防止手机进入休眠而切断触控 IC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 4. 将屏幕亮度压至极暗最低极限 (0.001f)，配合纯黑背景实现 OLED 零发光
        val lp = window.attributes
        lp.screenBrightness = 0.001f
        window.attributes = lp

        // 5. 触控板防截屏保护：开启 FLAG_SECURE 屏蔽三指误触手机系统截屏
        val antiScreenshot = prefs.getBoolean(MainActivity.KEY_ANTI_SCREENSHOT, true)
        if (antiScreenshot) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        binding = ActivityBlackScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 6. 返回键退出监听
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitBlackScreen("已退出黑屏触控模式")
            }
        })

        // 7. 顶部退出指引小卡片：展示 2.2 秒后平滑淡出，实现 100% 纯黑无光状态
        binding.layoutExitTip.setOnClickListener {
            exitBlackScreen("已退出黑屏触控模式")
        }
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                binding.layoutExitTip.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction { binding.layoutExitTip.visibility = View.GONE }
                    .start()
            }
        }, 2200)

        setupTouchpad()
    }

    private fun setupTouchpad() {
        // 读取并应用用户此前在设置中的个性化偏好
        val savedTouchpadSens = prefs.getFloat(MainActivity.KEY_TOUCHPAD_SENS, 1.2f)
        val savedWheelSens = prefs.getFloat(MainActivity.KEY_WHEEL_SENS, 0.2f)
        val savedTouchpadNatural = prefs.getBoolean(MainActivity.KEY_TOUCHPAD_SCROLL_DIRECTION, true)
        val isNative = (hidMouseManager.hidMode == HidMouseManager.MODE_TOUCHPAD)

        binding.touchpadView.sensitivity = savedTouchpadSens
        binding.touchpadView.scrollSensitivity = savedWheelSens
        binding.touchpadView.isNaturalScroll = savedTouchpadNatural
        binding.touchpadView.isNativeTouchpadMode = isNative

        // 全屏触控板手势回调处理
        binding.touchpadView.callback = object : cn.moonify.aircursor.ui.TouchpadView.Callback {
            override fun onMove(dx: Int, dy: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, dx, dy, 0, 0)
            }

            override fun onTap() {
                // 单指轻触：发送鼠标左键单击 (附带触觉反馈)
                binding.touchpadView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val downState = currentButtonState or HidMouseManager.BUTTON_LEFT
                hidMouseManager.sendMouseReport(downState, 0, 0, 0, 0)
                mainHandler.postDelayed({
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                }, 20)
            }

            override fun onSecondaryTap() {
                // 双指轻触：发送鼠标右键单击 (附带触觉反馈)
                binding.touchpadView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val downState = currentButtonState or HidMouseManager.BUTTON_RIGHT
                hidMouseManager.sendMouseReport(downState, 0, 0, 0, 0)
                mainHandler.postDelayed({
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                }, 20)
            }

            override fun onDragStart() {
                currentButtonState = currentButtonState or HidMouseManager.BUTTON_LEFT
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
            }

            override fun onDragMove(dx: Int, dy: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, dx, dy, 0, 0)
            }

            override fun onDragEnd() {
                currentButtonState = currentButtonState and HidMouseManager.BUTTON_LEFT.inv()
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
            }

            override fun onScroll(wheel: Int, pan: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, wheel, pan)
            }

            override fun onThreeFingerSwipeUp() {
                handleThreeFingerOverview("三指上滑")
            }

            override fun onThreeFingerSwipeDown() {
                binding.touchpadView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                hidMouseManager.sendConsumerKey(HidMouseManager.CONSUMER_HOME)
            }

            override fun onThreeFingerHorizontalSwipe(direction: Int) {
                handleDirectAppSwitch(direction)
            }

            override fun onThreeFingerTap() {
                handleThreeFingerOverview("三指轻点")
            }

            override fun onFourFingerTap() {
                exitBlackScreen("四指轻点：已退出黑屏触控模式")
            }
        }
    }

    /**
     * 监听实体按键：在盲操状态下，按下手机侧面任意音量键即可安全退出
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            exitBlackScreen("已按音量键退出黑屏触控模式")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleThreeFingerOverview(gestureName: String) {
        val hidConnected = hidMouseManager.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        val rfConnected = rfcommClientManager.connectionState == cn.moonify.aircursor.bluetooth.RfcommClientManager.STATE_CONNECTED
        val useWinTab = prefs.getString(MainActivity.KEY_RECENTS_SHORTCUT, MainActivity.SHORTCUT_ALT_TAB) == MainActivity.SHORTCUT_WIN_TAB

        binding.touchpadView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)

        if (rfConnected) {
            rfcommClientManager.sendCommand(RfcommConstants.CMD_RECENTS)
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendOverview(useWinTab)
        }
    }

    private fun handleDirectAppSwitch(direction: Int) {
        val hidConnected = hidMouseManager.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        val rfConnected = rfcommClientManager.connectionState == cn.moonify.aircursor.bluetooth.RfcommClientManager.STATE_CONNECTED
        val useAltTab = prefs.getString(MainActivity.KEY_RECENTS_SHORTCUT, MainActivity.SHORTCUT_ALT_TAB) != MainActivity.SHORTCUT_WIN_TAB
        val reverse = (direction < 0)

        binding.touchpadView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)

        if (rfConnected) {
            rfcommClientManager.sendCommand(RfcommConstants.CMD_RECENTS)
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendRecentApps(useAltTab, reverse)
        }
    }

    private fun exitBlackScreen(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
