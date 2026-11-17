package com.my.seekapplication

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * DashboardActivity: Vault gate screen with custom Swipe-to-Unlock slider.
 *
 * Implements:
 * 1. Smooth custom Swipe-to-Unlock slider physics.
 * 2. Support for PIN, Pattern, and Password lock mechanisms.
 * 3. Android 16 Window Insets: avoids UI overlap with status bar / navigation bar.
 * 4. Password Recovery: Security question & answer reset dialog accessible from unlock screens.
 * 5. Clean, uninterrupted flow: removed redundant popup on hold.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var swipeThumb: RelativeLayout
    private lateinit var swipeTrack: RelativeLayout
    private var dX = 0f

    private var isUnlockTriggered = false
    private var activeDialog: Dialog? = null

    // Setup caching
    private var tempRealKeySetup: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screenshots and screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_dashboard)

        val root = findViewById<RelativeLayout>(R.id.rootDashboard)

        // Android 16 Window Insets: avoid edge overlap
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            val sidePad = (16 * density).toInt()
            root.setPadding(
                systemBars.left + sidePad,
                systemBars.top + (12 * density).toInt(),
                systemBars.right + sidePad,
                systemBars.bottom + (12 * density).toInt()
            )
            insets
        }

        swipeThumb = findViewById(R.id.swipeThumb)
        swipeTrack = findViewById(R.id.swipeTrack)

        // SWIPE TO UNLOCK LOGIC with single-trigger guard
        swipeThumb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    isUnlockTriggered = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX
                    val maxX = swipeTrack.width.toFloat() - view.width.toFloat()

                    if (newX < 0) newX = 0f
                    if (newX > maxX) newX = maxX

                    view.x = newX

                    if (!isUnlockTriggered && newX >= maxX - 12f) {
                        isUnlockTriggered = true
                        view.animate().x(0f).setDuration(200).start()
                        onSwipeThresholdReached()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().x(0f).setDuration(200).start()
                    isUnlockTriggered = false
                    true
                }
                else -> false
            }
        }

        // Tap on Header or Lock icon allows direct recovery if user forgot password
        findViewById<View>(R.id.headerText)?.setOnClickListener {
            showPasswordRecoveryDialog()
        }
        findViewById<View>(R.id.secureLockImage)?.setOnClickListener {
            showPasswordRecoveryDialog()
        }

        // Handle direct recovery intent from VoiceRecorderActivity
        if (intent.getBooleanExtra("START_RECOVERY", false)) {
            swipeTrack.post {
                showPasswordRecoveryDialog()
            }
        }
    }

    private fun onSwipeThresholdReached() {
        if (isFinishing || isDestroyed) return
        if (activeDialog?.isShowing == true) return

        if (!VaultSecurityManager.hasPassword(this)) {
            // First time setup
            showChooseLockTypeDialog()
        } else {
            // Unlock with user's configured lock type
            when (VaultSecurityManager.getLockType(this)) {
                VaultSecurityManager.LockType.PATTERN -> showUnlockPatternDialog()
                VaultSecurityManager.LockType.PIN -> showUnlockTextDialog(isPin = true)
                VaultSecurityManager.LockType.PASSWORD -> showUnlockTextDialog(isPin = false)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FIRST TIME SETUP: CHOOSE LOCK TYPE
    // ---------------------------------------------------------------------------------------------

    private fun showChooseLockTypeDialog() {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_choose_lock_type)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        dialog.findViewById<View>(R.id.btnSelectPin).setOnClickListener {
            VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PIN)
            dialog.dismiss()
            showSetupTextDialog(isReal = true, isPin = true)
        }

        dialog.findViewById<View>(R.id.btnSelectPattern).setOnClickListener {
            VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PATTERN)
            dialog.dismiss()
            showSetupPatternDialog(isReal = true)
        }

        dialog.findViewById<View>(R.id.btnSelectPassword).setOnClickListener {
            VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PASSWORD)
            dialog.dismiss()
            showSetupTextDialog(isReal = true, isPin = false)
        }

        dialog.setCancelable(false)
        activeDialog = dialog
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // PATTERN LOCK: SETUP & UNLOCK
    // ---------------------------------------------------------------------------------------------

    private fun showSetupPatternDialog(isReal: Boolean) {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_pattern_lock)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost((380 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvPatternTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvPatternSubtitle)
        val patternView = dialog.findViewById<PatternLockView>(R.id.patternLockView)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelPattern)

        if (isReal) {
            tvTitle.text = "Set REAL Vault Pattern"
            tvSubtitle.text = "Connect at least 4 dots"
        } else {
            tvTitle.text = "Set FAKE Vault Pattern"
            tvSubtitle.text = "Draw a DIFFERENT pattern for decoy mode"
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            showChooseLockTypeDialog()
        }

        patternView.onPatternListener = { pattern ->
            if (pattern.length < 4) {
                Toast.makeText(this, "Connect at least 4 dots!", Toast.LENGTH_SHORT).show()
                patternView.setErrorState(true)
                patternView.postDelayed({ patternView.resetPattern() }, 600)
            } else {
                if (isReal) {
                    tempRealKeySetup = pattern
                    VaultSecurityManager.setPassword(this, pattern, isReal = true)
                    dialog.dismiss()
                    Toast.makeText(this, "Real Pattern Saved! Now set Fake Pattern.", Toast.LENGTH_SHORT).show()
                    showSetupPatternDialog(isReal = false)
                } else {
                    if (pattern == tempRealKeySetup) {
                        Toast.makeText(this, "Fake pattern cannot match Real pattern!", Toast.LENGTH_LONG).show()
                        patternView.setErrorState(true)
                        patternView.postDelayed({ patternView.resetPattern() }, 600)
                    } else {
                        VaultSecurityManager.setPassword(this, pattern, isReal = false)
                        tempRealKeySetup = null
                        dialog.dismiss()
                        promptSecurityQuestionSetup()
                    }
                }
            }
        }

        dialog.setCancelable(false)
        activeDialog = dialog
        dialog.show()
    }

    private fun showUnlockPatternDialog() {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_pattern_lock)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost((380 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvPatternTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvPatternSubtitle)
        val patternView = dialog.findViewById<PatternLockView>(R.id.patternLockView)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelPattern)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordPattern)

        tvTitle.text = "Draw Vault Pattern"
        tvSubtitle.text = "Connect dots to unlock"

        btnForgot.visibility = View.VISIBLE
        btnForgot.setOnClickListener {
            dialog.dismiss()
            showPasswordRecoveryDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        patternView.onPatternListener = { pattern ->
            when (VaultSecurityManager.checkPassword(this, pattern)) {
                VaultSecurityManager.PasswordCheckResult.REAL -> {
                    failedUnlockAttempts = 0
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = false)
                }
                VaultSecurityManager.PasswordCheckResult.DECOY -> {
                    failedUnlockAttempts = 0
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = true)
                }
                VaultSecurityManager.PasswordCheckResult.INVALID -> {
                    patternView.setErrorState(true)
                    Toast.makeText(this, "Wrong Pattern! Incorrect entry.", Toast.LENGTH_SHORT).show()
                    handleFailedAttempt()
                    patternView.postDelayed({ patternView.resetPattern() }, 800)
                }
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // PIN & PASSWORD: SETUP & UNLOCK
    // ---------------------------------------------------------------------------------------------

    private fun showSetupTextDialog(isReal: Boolean, isPin: Boolean) {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val dialogInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val dialogSetButton = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)
        btnForgot.visibility = View.GONE

        if (isPin) {
            dialogInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            dialogTitle.text = if (isReal) "Set REAL Numeric PIN" else "Set FAKE Numeric PIN"
            dialogInput.hint = if (isReal) "4-Digit Real PIN" else "4-Digit Fake PIN"
        } else {
            dialogInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            dialogTitle.text = if (isReal) "Set REAL Password" else "Set FAKE Password"
            dialogInput.hint = if (isReal) "Real Password" else "Fake Password"
        }
        dialogSetButton.text = "SAVE"

        dialogSetButton.setOnClickListener {
            val key = dialogInput.text.toString().trim()
            val minLen = 4

            if (key.length < minLen) {
                Toast.makeText(this, "${if (isPin) "PIN" else "Password"} must be at least $minLen characters!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isReal) {
                tempRealKeySetup = key
                VaultSecurityManager.setPassword(this, key, isReal = true)
                dialog.dismiss()
                Toast.makeText(this, "Real ${if (isPin) "PIN" else "Password"} Saved!", Toast.LENGTH_SHORT).show()
                showSetupTextDialog(isReal = false, isPin = isPin)
            } else {
                if (key == tempRealKeySetup) {
                    Toast.makeText(this, "Fake key cannot be identical to Real key!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                VaultSecurityManager.setPassword(this, key, isReal = false)
                tempRealKeySetup = null
                dialog.dismiss()
                promptSecurityQuestionSetup()
            }
        }

        dialog.setCancelable(false)
        activeDialog = dialog
        dialog.show()
    }

    private fun showUnlockTextDialog(isPin: Boolean) {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val dialogInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val dialogSetButton = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)

        btnForgot.visibility = View.VISIBLE
        btnForgot.setOnClickListener {
            dialog.dismiss()
            showPasswordRecoveryDialog()
        }

        if (isPin) {
            dialogInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            dialogTitle.text = "Enter Vault PIN"
            dialogInput.hint = "Your PIN"
        } else {
            dialogInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            dialogTitle.text = "Enter Vault Password"
            dialogInput.hint = "Your Password"
        }
        dialogSetButton.text = "UNLOCK"

        dialogSetButton.setOnClickListener {
            val entered = dialogInput.text.toString().trim()

            when (VaultSecurityManager.checkPassword(this, entered)) {
                VaultSecurityManager.PasswordCheckResult.REAL -> {
                    failedUnlockAttempts = 0
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = false)
                }
                VaultSecurityManager.PasswordCheckResult.DECOY -> {
                    failedUnlockAttempts = 0
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = true)
                }
                VaultSecurityManager.PasswordCheckResult.INVALID -> {
                    dialogInput.text.clear()
                    Toast.makeText(this, "Wrong ${if (isPin) "PIN" else "Password"}! Incorrect entry.", Toast.LENGTH_SHORT).show()
                    handleFailedAttempt()
                }
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    private var failedUnlockAttempts = 0

    private fun handleFailedAttempt() {
        failedUnlockAttempts++
        try { IntruderCamera.takeSecuritySelfie(this) } catch (ignored: Exception) {}
        if (failedUnlockAttempts >= 3) {
            AlertDialog.Builder(this)
                .setTitle("Trouble Unlocking Vault?")
                .setMessage("You entered an incorrect key 3 times. Would you like to reset your vault key using your secret Security Recovery Question?")
                .setPositiveButton("RESET KEY NOW") { _, _ ->
                    activeDialog?.dismiss()
                    showPasswordRecoveryDialog()
                }
                .setNegativeButton("TRY AGAIN", null)
                .show()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD RECOVERY / RESET DIALOG
    // ---------------------------------------------------------------------------------------------

    private fun promptSecurityQuestionSetup() {
        if (VaultSecurityManager.hasSecurityQuestion(this)) {
            openVaultDirectly()
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_security_recovery)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvRecoveryTitle)
        val tvQuestion = dialog.findViewById<TextView>(R.id.tvQuestionDisplay)
        val etAnswer = dialog.findViewById<EditText>(R.id.etRecoveryAnswer)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        tvTitle.text = "Set Recovery Question"
        tvQuestion.text = "Security Question:\nWhat was your first school or favorite keyword?"
        etAnswer.hint = "Your secret recovery answer"
        btnSubmit.text = "FINISH SETUP"
        btnCancel.visibility = View.GONE

        btnSubmit.setOnClickListener {
            val ans = etAnswer.text.toString().trim()
            if (ans.length < 3) {
                Toast.makeText(this, "Answer must be at least 3 characters!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VaultSecurityManager.setSecurityRecovery(this, "What was your first school or favorite keyword?", ans)
            dialog.dismiss()
            Toast.makeText(this, "Recovery question saved!", Toast.LENGTH_SHORT).show()
            openVaultDirectly()
        }

        dialog.setCancelable(false)
        dialog.show()
    }

    private fun showPasswordRecoveryDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_security_recovery)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvQuestion = dialog.findViewById<TextView>(R.id.tvQuestionDisplay)
        val etAnswer = dialog.findViewById<EditText>(R.id.etRecoveryAnswer)
        val etNewPass = dialog.findViewById<EditText>(R.id.etNewPasswordReset)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        var isVerified = false
        val q = if (VaultSecurityManager.hasSecurityQuestion(this)) {
            VaultSecurityManager.getSecurityQuestion(this)
        } else {
            "What was your first school or favorite keyword?"
        }
        tvQuestion.text = "Security Question:\n$q"

        btnSubmit.setOnClickListener {
            if (!isVerified) {
                val ans = etAnswer.text.toString().trim()
                if (VaultSecurityManager.verifySecurityAnswer(this, ans) || !VaultSecurityManager.hasSecurityQuestion(this)) {
                    isVerified = true
                    val currentLockType = VaultSecurityManager.getLockType(this)
                    if (currentLockType == VaultSecurityManager.LockType.PATTERN) {
                        dialog.dismiss()
                        Toast.makeText(this, "Identity verified! Please draw your new pattern.", Toast.LENGTH_LONG).show()
                        showSetupPatternDialog(isReal = true)
                    } else {
                        etAnswer.visibility = View.GONE
                        etNewPass.visibility = View.VISIBLE
                        val isPin = (currentLockType == VaultSecurityManager.LockType.PIN)
                        if (isPin) {
                            etNewPass.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                            etNewPass.hint = "New 4-digit PIN"
                        } else {
                            etNewPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            etNewPass.hint = "New Password"
                        }
                        btnSubmit.text = "SAVE NEW KEY"
                        Toast.makeText(this, "Identity verified! Enter new key.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Incorrect answer!", Toast.LENGTH_SHORT).show()
                }
            } else {
                val newKey = etNewPass.text.toString().trim()
                if (newKey.length < 4) {
                    Toast.makeText(this, "Key must be at least 4 characters!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                VaultSecurityManager.setPassword(this, newKey, isReal = true)
                Toast.makeText(this, "Vault password reset successfully!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                openVaultDirectly()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun openVaultDirectly() {
        Toast.makeText(this, "Setup Complete! Opening Vault...", Toast.LENGTH_SHORT).show()
        launchVaultGallery(isDecoy = false)
    }

    private fun launchVaultGallery(isDecoy: Boolean) {
        val intent = Intent(this, VaultGalleryActivity::class.java)
        intent.putExtra("IS_DECOY", isDecoy)
        startActivity(intent)
        finish()
    }
}
